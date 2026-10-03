package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Protocol 23.2 sound slice: world.playSound (a sound at a position) and world.playBlockSound
 * (the sound of the block at a position). options is {volume?, pitch?, note?, receiver?};
 * pitch and note are mutually exclusive. The source category is fixed in b8
 * (master／block).
 */
final class SoundCommands {
    static final Set<String> OPTION_FIELDS = Set.of("volume", "pitch", "note", "receiver");
    static final Set<String> BLOCK_KINDS = Set.of("place", "hit", "break", "step", "fall");
    static final double MIN_PITCH = 0.5;
    static final double MAX_PITCH = 2.0;
    static final int MAX_NOTE = 24;
    static final int WORK_UNITS = 1;

    private final B7CommandContext session;
    private final SoundRateAdmission rateAdmission;
    private final Predicate<NamespacedKey> registeredSound;
    private final BlockSoundLookup blockSounds;
    private final Function<UUID, Player> onlinePlayers;

    /** A block's sound for one kind, with the SoundGroup default volume and pitch. */
    record BlockSound(String soundId, float volume, float pitch) {
    }

    @FunctionalInterface
    interface BlockSoundLookup {
        BlockSound lookup(BlockData data, String kind);
    }

    SoundCommands(B7CommandContext session, SoundRateAdmission rateAdmission) {
        this(session, rateAdmission, key -> Registry.SOUND_EVENT.get(key) != null,
                SoundCommands::blockSound, Bukkit::getPlayer);
    }

    SoundCommands(
            B7CommandContext session,
            SoundRateAdmission rateAdmission,
            Predicate<NamespacedKey> registeredSound,
            BlockSoundLookup blockSounds,
            Function<UUID, Player> onlinePlayers
    ) {
        this.session = session;
        this.rateAdmission = rateAdmission;
        this.registeredSound = registeredSound;
        this.blockSounds = blockSounds;
        this.onlinePlayers = onlinePlayers;
    }

    void register(CommandRegistry registry) {
        registry.registerStructured("world.playSound", this::handlePlaySound);
        registry.registerStructured("world.playBlockSound", this::handlePlayBlockSound);
    }

    /** Params: [x, y, z, sound_id, options?]. Result null. */
    void handlePlaySound(JsonElement params) {
        Location location;
        String soundId;
        Options options;
        try {
            JsonArray args = WireParams.positional(params, 4, 5);
            location = relativeLocation(args);
            soundId = WireParams.string(args, 3);
            options = Options.parse(args.size() == 5 ? args.get(4) : null);
        } catch (IllegalArgumentException | ArithmeticException e) {
            invalidParams();
            return;
        }
        NamespacedKey key = canonicalKey(soundId);
        if (key == null || !registeredSound.test(key)) {
            session.respondError(-32602, "unknown_sound", null);
            return;
        }
        Receiver receiver = resolveReceiver(options);
        if (receiver == null || !preflight(location)) {
            return;
        }
        float pitch = options.pitchOr(1.0f);
        float volume = options.volumeOr(1.0f);
        play(receiver, location, key.toString(), SoundCategory.MASTER, volume, pitch);
    }

    /** Params: [x, y, z, kind, options?] with integer block coordinates. Result null. */
    void handlePlayBlockSound(JsonElement params) {
        Location location;
        String kind;
        Options options;
        try {
            JsonArray args = WireParams.positional(params, 4, 5);
            Location origin = requireOrigin();
            int x = Math.addExact(origin.getBlockX(), WireParams.integer(args, 0));
            int y = Math.addExact(origin.getBlockY(), WireParams.integer(args, 1));
            int z = Math.addExact(origin.getBlockZ(), WireParams.integer(args, 2));
            location = new Location(origin.getWorld(), x, y, z);
            kind = WireParams.string(args, 3);
            if (!BLOCK_KINDS.contains(kind)) {
                throw new IllegalArgumentException("unknown block sound kind");
            }
            options = Options.parse(args.size() == 5 ? args.get(4) : null);
        } catch (IllegalArgumentException | ArithmeticException e) {
            invalidParams();
            return;
        }
        Receiver receiver = resolveReceiver(options);
        if (receiver == null || !preflight(location)) {
            return;
        }
        World world = location.getWorld();
        try {
            if (!WorldB5Commands.ensureChunkLoaded(
                    world, location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                session.respondError(-32000, "backpressure", null);
                return;
            }
        } catch (Exception e) {
            session.respondError(-32000, "backpressure", null);
            return;
        }
        BlockData data = world.getBlockAt(
                location.getBlockX(), location.getBlockY(), location.getBlockZ()).getBlockData();
        if (isAir(data.getMaterial())) {
            session.respondError(-32000, "no_block", null);
            return;
        }
        BlockSound sound = blockSounds.lookup(data, kind);
        // Sound comes from the block's centre.
        Location centre = location.clone().add(0.5, 0.5, 0.5);
        play(receiver, centre, sound.soundId(), SoundCategory.BLOCKS,
                options.volumeOr(sound.volume()), options.pitchOr(sound.pitch()));
    }

    private void play(Receiver receiver, Location location, String sound, SoundCategory category,
                      float volume, float pitch) {
        try {
            if (receiver.player() != null) {
                receiver.player().playSound(location, sound, category, volume, pitch);
            } else {
                location.getWorld().playSound(location, sound, category, volume, pitch);
            }
            session.respondResult(null);
        } catch (Exception e) {
            session.respondError(-32000, "internal_error", null);
        }
    }

    /** receiver syntax was checked while parsing; resolves self to the bound online player. */
    private Receiver resolveReceiver(Options options) {
        if (!options.self()) {
            return new Receiver(null);
        }
        UUID player = session.getBoundUuid();
        if (player == null) {
            session.respondError(-32000, "auth_required", null);
            return null;
        }
        Player online = onlinePlayers.apply(player);
        if (online == null || !online.isOnline()) {
            session.respondError(-32000, "player_offline", null);
            return null;
        }
        return new Receiver(online);
    }

    /** permission → build range → per-tick sound cap → work. */
    private boolean preflight(Location location) {
        if (!session.hasConstructionPermission()) {
            session.respondError(-32000, "permission_denied", null);
            return false;
        }
        if (!session.isWithinBuildRange(location)) {
            session.respondError(-32000, "build_denied", null);
            return false;
        }
        if (rateAdmission.admit(session.getConnectionEpoch()) == SoundRateAdmission.Result.BACKPRESSURE) {
            session.rejectTemporaryBackpressure();
            return false;
        }
        WorkAdmission.Result work = session.admitWork(WORK_UNITS);
        if (work == WorkAdmission.Result.BACKPRESSURE) {
            session.rejectTemporaryBackpressure();
            return false;
        }
        if (work == WorkAdmission.Result.WORK_LIMIT_EXCEEDED) {
            session.respondError(-32000, "work_limit_exceeded", null);
            return false;
        }
        return true;
    }

    private Location relativeLocation(JsonArray args) {
        Location origin = requireOrigin();
        double x = origin.getX() + WireParams.finiteDouble(args, 0);
        double y = origin.getY() + WireParams.finiteDouble(args, 1);
        double z = origin.getZ() + WireParams.finiteDouble(args, 2);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("absolute coordinates must be finite");
        }
        return new Location(origin.getWorld(), x, y, z);
    }

    private Location requireOrigin() {
        Location origin = session.getOrigin();
        if (origin == null || origin.getWorld() == null) {
            throw new IllegalArgumentException("origin is not set");
        }
        return origin;
    }

    private void invalidParams() {
        session.respondError(-32602, "invalid_params", null);
    }

    static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    /** note n → playback rate 2^((n - 12) / 12): 12 is the original pitch, 0 and 24 one octave down/up. */
    static float noteToPitch(int note) {
        return (float) Math.pow(2.0, (note - 12) / 12.0);
    }

    /** minecraft: may be omitted and is filled in; see {@link ResourceIds}. */
    static NamespacedKey canonicalKey(String raw) {
        return ResourceIds.parse(raw);
    }

    static BlockSound blockSound(BlockData data, String kind) {
        SoundGroup group = data.getSoundGroup();
        org.bukkit.Sound sound = switch (kind) {
            case "place" -> group.getPlaceSound();
            case "hit" -> group.getHitSound();
            case "break" -> group.getBreakSound();
            case "step" -> group.getStepSound();
            default -> group.getFallSound();
        };
        return new BlockSound(sound.getKey().toString(), group.getVolume(), group.getPitch());
    }

    private record Receiver(Player player) {
    }

    /** Parsed options. volume／pitch are null when omitted; note is already converted to pitch. */
    record Options(Float volume, Float pitch, boolean self) {
        float volumeOr(float fallback) {
            return volume == null ? fallback : volume;
        }

        float pitchOr(float fallback) {
            return pitch == null ? fallback : pitch;
        }

        static Options parse(JsonElement element) {
            if (element == null) {
                return new Options(null, null, false);
            }
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("options must be an object");
            }
            JsonObject options = element.getAsJsonObject();
            for (String key : options.keySet()) {
                if (!OPTION_FIELDS.contains(key)) {
                    throw new IllegalArgumentException("unknown option");
                }
            }
            if (options.has("pitch") && options.has("note")) {
                throw new IllegalArgumentException("pitch and note are mutually exclusive");
            }
            Float volume = null;
            if (options.has("volume")) {
                double value = finite(options.get("volume"));
                if (value < 0.0 || value > 1.0) {
                    throw new IllegalArgumentException("volume must be within 0..1");
                }
                volume = (float) value;
            }
            Float pitch = null;
            if (options.has("pitch")) {
                double value = finite(options.get("pitch"));
                if (value < MIN_PITCH || value > MAX_PITCH) {
                    throw new IllegalArgumentException("pitch must be within 0.5..2.0");
                }
                pitch = (float) value;
            }
            if (options.has("note")) {
                int note = integer(options.get("note"));
                if (note < 0 || note > MAX_NOTE) {
                    throw new IllegalArgumentException("note must be within 0..24");
                }
                pitch = noteToPitch(note);
            }
            boolean self = false;
            if (options.has("receiver")) {
                JsonElement receiver = options.get("receiver");
                if (receiver == null || !receiver.isJsonPrimitive() || !receiver.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("receiver must be a string");
                }
                String mode = receiver.getAsString();
                if ("self".equals(mode)) {
                    self = true;
                } else if (!"world".equals(mode)) {
                    throw new IllegalArgumentException("receiver must be world or self");
                }
            }
            return new Options(volume, pitch, self);
        }

        private static double finite(JsonElement element) {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("must be a number");
            }
            double value = element.getAsDouble();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("must be finite");
            }
            return value;
        }

        private static int integer(JsonElement element) {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("must be an integer");
            }
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            try {
                return new BigDecimal(primitive.getAsString()).intValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("must be an integer", e);
            }
        }
    }
}
