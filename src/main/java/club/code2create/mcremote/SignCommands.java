package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * b6 sign three-op slice: world.setSign / world.getSign / world.updateSignLine. The exact wire
 * contract (LineSpec/LineValue shape, allowed color/decoration tokens, canonical output, result
 * and error reasons) is locked by DECISIONS 2026-08-26-05; this implementation is the plugin
 * candidate that decision carries (McRemote codex/b6-set-sign@a34fec0). Method-set state (shared
 * fixture, cross-client parity, formal evidence, release) remains `candidate` until those separate
 * gates close.
 *
 * world.setSign/world.getSign are pure PUT/GET (no-merge, whole-face replace). world.updateSignLine
 * is the sign-specific PATCH primitive discussed in that session: it targets exactly one line on
 * one face and leaves everything else untouched, kept as a separate method rather than folded into
 * setSign's params shape so no single method mixes PUT and PATCH semantics.
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
    // Vanilla's rendered default for a sign line with no explicit color. Ratified by DECISIONS
    // 2026-08-26-05 ("無色をblackへ正規化する").
    private static final String DEFAULT_COLOR_TOKEN = "black";
    // Fixed to Minecraft's traditional 16-color order (not alphabetical) to byte-match the shared
    // sign-v23.json fixture's invalid_property_value.data.allowed list (DECISIONS 2026-08-26-05
    // fixes the token vocabulary; this repo and scratch-editor's fixture agree on this list order).
    private static final List<Object> ALLOWED_COLOR_TOKENS = List.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "#RRGGBB");
    // Fixed order (not alphabetical) to byte-match the fixture's data.allowed list. Distinct from
    // LineValue's canonical *output* decorations array, which stays alphabetically sorted.
    private static final List<Object> ALLOWED_DECORATION_TOKENS = List.of(
            "bold", "italic", "underlined", "strikethrough", "obfuscated");

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

    /** Params: [x, y, z]. Always readable regardless of waxed state (waxed only blocks writes). */
    void handleGetSign(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 3);
            int x = WireParams.integer(args, 0);
            int y = WireParams.integer(args, 1);
            int z = WireParams.integer(args, 2);
            Location target = miscCommands.parseRelativeBlockLocation(x, y, z);
            World world = target.getWorld();
            // Same pattern as world.getBlock: reads auto-load/generate the chunk, no admission gate.
            Block block = world.getBlockAt(target);
            BlockState state = block.getState();
            if (!(state instanceof Sign sign)) {
                session.respondError(-32000, "not_a_sign", null);
                return;
            }
            session.respondResult(encode(sign));
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", pathData("params"));
            logger.warning("Invalid parameters for world.getSign: " + e.getMessage());
        }
    }

    /**
     * Params: [x, y, z, face, line_index, LineSpec]. PATCH-style: replaces exactly one line on
     * one face, leaving the other three lines on that face and the entire other face untouched.
     * Kept as a separate method from world.setSign (PUT, no-merge) so no single method mixes PUT
     * and PATCH semantics — see the class-level note.
     */
    void handleUpdateSignLine(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 6);
            int x = WireParams.integer(args, 0);
            int y = WireParams.integer(args, 1);
            int z = WireParams.integer(args, 2);
            Side face = parseFace(args, 3);
            int lineIndex = parseLineIndex(args, 4);
            LineSpec spec = lineSpec(args.get(5), "params[5]");

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

            // Validation is complete before this point; the single update() call below is the
            // only point the world changes, and it touches only the one targeted line.
            Sign sign = (Sign) state;
            sign.getSide(face).line(lineIndex, componentFor(spec));
            if (!sign.update(false, false)) {
                session.respondError(-32000, "sign_update_failed", null);
                logger.warning("world.updateSignLine: update() rejected a stale BlockState snapshot.");
                return;
            }
            session.respondResult(null);
        } catch (ValidationException e) {
            session.respondError(-32602, e.reason, e.data);
            logger.warning("Invalid line spec for world.updateSignLine: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", pathData("params"));
            logger.warning("Invalid parameters for world.updateSignLine: " + e.getMessage());
        }
    }

    private static Side parseFace(JsonArray args, int index) {
        String value = WireParams.string(args, index);
        return switch (value) {
            case "front" -> Side.FRONT;
            case "back" -> Side.BACK;
            default -> throw new IllegalArgumentException("face must be \"front\" or \"back\"");
        };
    }

    private static int parseLineIndex(JsonArray args, int index) {
        int value = WireParams.integer(args, index);
        if (value < 0 || value >= LINE_COUNT) {
            throw new IllegalArgumentException("line_index must be between 0 and " + (LINE_COUNT - 1));
        }
        return value;
    }

    private static void apply(Sign sign, SignSpec spec) {
        if (spec.front != null) {
            applyLines(sign.getSide(Side.FRONT), spec.front);
        }
        if (spec.back != null) {
            applyLines(sign.getSide(Side.BACK), spec.back);
        }
    }

    private static void applyLines(SignSide side, LineSpec[] lines) {
        // Component-based line(int, Component), not the deprecated String-based setLine. Each
        // line is a fresh Component built from scratch (no merge with the previous line's style).
        for (int i = 0; i < LINE_COUNT; i++) {
            side.line(i, componentFor(lines[i]));
        }
    }

    static Component componentFor(LineSpec spec) {
        Component component = Component.text(spec.text).color(spec.color);
        for (TextDecoration decoration : TextDecoration.values()) {
            component = component.decoration(decoration, spec.decorations.contains(decoration));
        }
        return component;
    }

    static Map<String, Object> encode(Sign sign) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("front", encodeSide(sign.getSide(Side.FRONT)));
        result.put("back", encodeSide(sign.getSide(Side.BACK)));
        result.put("waxed", sign.isWaxed());
        return Map.copyOf(result);
    }

    private static List<Object> encodeSide(SignSide side) {
        List<Object> lines = new ArrayList<>(LINE_COUNT);
        for (int i = 0; i < LINE_COUNT; i++) {
            lines.add(encodeLine(side.line(i)));
        }
        return List.copyOf(lines);
    }

    static Map<String, Object> encodeLine(Component component) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", PlainTextComponentSerializer.plainText().serialize(component));
        value.put("color", encodeColor(component.color()));
        value.put("decorations", encodeDecorations(component));
        return Map.copyOf(value);
    }

    static String encodeColor(TextColor color) {
        if (color == null) {
            return DEFAULT_COLOR_TOKEN;
        }
        NamedTextColor nearest = NamedTextColor.nearestTo(color);
        if (nearest.value() == color.value()) {
            return NamedTextColor.NAMES.key(nearest);
        }
        return color.asHexString();
    }

    /** Canonical output is a sorted array of decoration tokens (only the ones explicitly on). */
    static List<Object> encodeDecorations(Component component) {
        List<Object> decorations = new ArrayList<>();
        for (TextDecoration decoration : TextDecoration.values()) {
            if (component.decoration(decoration) == TextDecoration.State.TRUE) {
                decorations.add(TextDecoration.NAMES.key(decoration));
            }
        }
        decorations.sort((left, right) -> ((String) left).compareTo((String) right));
        return List.copyOf(decorations);
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
        final LineSpec[] front;
        final LineSpec[] back;

        private SignSpec(LineSpec[] front, LineSpec[] back) {
            this.front = front;
            this.back = back;
        }
    }

    /** One line's canonical content after parsing: shorthand string or {text,color?,decorations?}. */
    static final class LineSpec {
        final String text;
        final TextColor color;
        final Set<TextDecoration> decorations;

        LineSpec(String text, TextColor color, Set<TextDecoration> decorations) {
            this.text = text;
            this.color = color;
            this.decorations = decorations;
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
        LineSpec[] front = spec.has("front") ? lines(spec.get("front"), "params[3].front") : null;
        LineSpec[] back = spec.has("back") ? lines(spec.get("back"), "params[3].back") : null;
        if (front == null && back == null) {
            throw invalid("params[3]", "at least one of front/back is required");
        }
        return new SignSpec(front, back);
    }

    private static LineSpec[] lines(JsonElement element, String path) throws ValidationException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != LINE_COUNT) {
            throw invalid(path, "must be an array of exactly " + LINE_COUNT + " lines");
        }
        JsonArray array = element.getAsJsonArray();
        LineSpec[] lines = new LineSpec[LINE_COUNT];
        for (int i = 0; i < LINE_COUNT; i++) {
            lines[i] = lineSpec(array.get(i), path + "[" + i + "]");
        }
        return lines;
    }

    private static LineSpec lineSpec(JsonElement value, String path) throws ValidationException {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return new LineSpec(lineText(value, path), null, Set.of());
        }
        if (value == null || !value.isJsonObject()) {
            throw invalid(path, "line must be a string or {text,color?,decorations?} object");
        }
        JsonObject line = value.getAsJsonObject();
        for (String key : line.keySet()) {
            if (!"text".equals(key) && !"color".equals(key) && !"decorations".equals(key)) {
                throw invalid(path + "." + key, "unknown line field");
            }
        }
        if (!line.has("text")) {
            throw invalid(path + ".text", "missing text");
        }
        String text = lineText(line.get("text"), path + ".text");
        TextColor color = line.has("color") ? lineColor(line.get("color"), path + ".color") : null;
        Set<TextDecoration> decorations = line.has("decorations")
                ? lineDecorations(line.get("decorations"), path + ".decorations")
                : Set.of();
        return new LineSpec(text, color, decorations);
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

    private static TextColor lineColor(JsonElement value, String path) throws ValidationException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(path, "color must be a string");
        }
        String token = value.getAsString();
        NamedTextColor named = NamedTextColor.NAMES.value(token);
        if (named != null) {
            return named;
        }
        TextColor hex = TextColor.fromHexString(token);
        if (hex != null) {
            return hex;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("property", "color");
        data.put("value", token);
        data.put("allowed", ALLOWED_COLOR_TOKENS);
        throw new ValidationException("invalid_property_value", data, "unknown color token: " + token);
    }

    private static Set<TextDecoration> lineDecorations(JsonElement value, String path) throws ValidationException {
        if (value == null || !value.isJsonArray()) {
            throw invalid(path, "decorations must be an array");
        }
        JsonArray array = value.getAsJsonArray();
        Set<TextDecoration> decorations = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            String elementPath = path + "[" + i + "]";
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw invalid(elementPath, "decoration token must be a string");
            }
            String token = element.getAsString();
            TextDecoration decoration = TextDecoration.NAMES.value(token);
            if (decoration == null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("property", "decorations");
                data.put("value", token);
                data.put("allowed", ALLOWED_DECORATION_TOKENS);
                throw new ValidationException("invalid_property_value", data, "unknown decoration token: " + token);
            }
            decorations.add(decoration);
        }
        return Set.copyOf(decorations);
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
