package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * b6 candidate: world.setSign. Exact wire shape is not yet ratified (DECISIONS 2026-08-16-06
 * names "sign 4-line/face/state validation and rollback" without fixing params/result); this is
 * an implementation candidate pending a knowledge-repo confirmation ticket, following the same
 * candidate-then-ratify precedent as world.getBlocks (2026-08-19-03).
 *
 * Depends on the front/back dual-text Sign API introduced in Minecraft 1.20 (org.bukkit.block.Sign
 * #getSide(Side), #isWaxed(), org.bukkit.block.sign.SignSide). Pre-1.20 servers only have a single
 * unsplit set of 4 lines directly on Sign and no waxed state — if this plugin's floor version is
 * ever lowered below 1.20, this method's contract cannot be implemented as-is and needs redesign.
 */
final class SignCommands {
    private static final Logger logger = Logger.getLogger("McR_Sign");
    private static final int LINE_COUNT = 4;
    // Provisional bound, not a protocol invariant: pending cross-repo ratification like other
    // b5/b6 finite placeholder values (queue/ring/particle/work limits).
    private static final int MAX_LINE_CODEPOINTS = 64;

    private final RemoteSession session;
    private final MiscCommands miscCommands;

    SignCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
    }

    /** Params: [x, y, z, {front?: [4 lines], back?: [4 lines]}]. At least one face is required. */
    void handleSetSign(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 4);
            int x = WireParams.integer(args, 0);
            int y = WireParams.integer(args, 1);
            int z = WireParams.integer(args, 2);
            SignSpec spec = parseSpec(args.get(3));

            Location target = miscCommands.parseRelativeBlockLocation(x, y, z);
            if (!session.hasConstructionPermission()) {
                session.respondError(-32000, "permission_denied", null);
                return;
            }
            if (!session.isWithinBuildRange(target)) {
                session.respondError(-32000, "build_denied", null);
                return;
            }
            if (!session.admitSetterWork(1)) {
                return;
            }
            World world = target.getWorld();
            if (!WorldB5Commands.ensureChunkLoaded(world, target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
                session.respondError(-32000, "backpressure", null);
                return;
            }

            Block block = world.getBlockAt(target);
            BlockState state = block.getState();
            SignAvailability availability = checkAvailability(state);
            if (availability != SignAvailability.OK) {
                session.respondError(-32000, availability.reason, null);
                return;
            }

            // Validation is complete before this point; apply() only mutates an in-memory
            // snapshot, and the single update() call below is the only point the world changes.
            Sign sign = (Sign) state;
            apply(sign, spec);
            if (!sign.update(false, false)) {
                // The block stopped being this sign between getState() and update() (e.g. a
                // concurrent edit). update() with force=false refuses to apply in that case, so
                // the world is unchanged — this is not a partial write.
                session.respondError(-32000, "sign_update_failed", null);
                logger.warning("world.setSign: update() rejected a stale BlockState snapshot.");
                return;
            }
            session.respondResult(null);
        } catch (ValidationException e) {
            session.respondError(-32602, e.reason, e.data);
            logger.warning("Invalid sign spec for world.setSign: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", pathData("params"));
            logger.warning("Invalid parameters for world.setSign: " + e.getMessage());
        }
    }

    private static void apply(Sign sign, SignSpec spec) {
        if (spec.front != null) {
            applyLines(sign.getSide(Side.FRONT), spec.front);
        }
        if (spec.back != null) {
            applyLines(sign.getSide(Side.BACK), spec.back);
        }
    }

    private static void applyLines(SignSide side, String[] lines) {
        // Component-based line(int, Component), not the deprecated String-based setLine —
        // still plain unstyled text on the wire, just the current (non-deprecated) Paper API.
        for (int i = 0; i < LINE_COUNT; i++) {
            side.line(i, Component.text(lines[i]));
        }
    }

    /** Pure classification of a captured BlockState against the b6 setSign preconditions. */
    enum SignAvailability {
        OK(null),
        NOT_A_SIGN("not_a_sign"),
        WAXED("sign_waxed");

        final String reason;

        SignAvailability(String reason) {
            this.reason = reason;
        }
    }

    static SignAvailability checkAvailability(BlockState state) {
        if (!(state instanceof Sign sign)) {
            return SignAvailability.NOT_A_SIGN;
        }
        if (sign.isWaxed()) {
            return SignAvailability.WAXED;
        }
        return SignAvailability.OK;
    }

    /** Pure, Bukkit-independent parse/validation of the sign_spec wire object. */
    static final class SignSpec {
        final String[] front;
        final String[] back;

        private SignSpec(String[] front, String[] back) {
            this.front = front;
            this.back = back;
        }
    }

    static SignSpec parseSpec(JsonElement element) throws ValidationException {
        if (element == null || !element.isJsonObject()) {
            throw invalid("params[3]", "sign spec must be an object");
        }
        JsonObject spec = element.getAsJsonObject();
        for (String key : spec.keySet()) {
            if (!"front".equals(key) && !"back".equals(key)) {
                throw invalid("params[3]." + key, "unknown sign spec field");
            }
        }
        String[] front = spec.has("front") ? lines(spec.get("front"), "params[3].front") : null;
        String[] back = spec.has("back") ? lines(spec.get("back"), "params[3].back") : null;
        if (front == null && back == null) {
            throw invalid("params[3]", "at least one of front/back is required");
        }
        return new SignSpec(front, back);
    }

    private static String[] lines(JsonElement element, String path) throws ValidationException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != LINE_COUNT) {
            throw invalid(path, "must be an array of exactly " + LINE_COUNT + " lines");
        }
        JsonArray array = element.getAsJsonArray();
        String[] lines = new String[LINE_COUNT];
        for (int i = 0; i < LINE_COUNT; i++) {
            lines[i] = lineText(array.get(i), path + "[" + i + "]");
        }
        return lines;
    }

    private static String lineText(JsonElement value, String path) throws ValidationException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(path, "line must be a string");
        }
        String text = value.getAsString();
        if (text.codePointCount(0, text.length()) > MAX_LINE_CODEPOINTS) {
            throw invalid(path, "line exceeds " + MAX_LINE_CODEPOINTS + " code points");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw invalid(path, "line must not contain control characters");
        }
        return text;
    }

    private static ValidationException invalid(String path, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return new ValidationException("invalid_params", data, detail);
    }

    private static Map<String, Object> pathData(String path) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return data;
    }

    static final class ValidationException extends Exception {
        final String reason;
        final Map<String, Object> data;

        ValidationException(String reason, Map<String, Object> data, String detail) {
            super(detail);
            this.reason = reason;
            this.data = Map.copyOf(data);
        }
    }
}
