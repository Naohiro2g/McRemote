package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** b5 world query/spawn commands with validation before any world mutation. */
final class WorldB5Commands {
    private final WorldCommandContext session;
    private final EntityHandleRegistry handles;
    private final RuntimePolicy policy;
    private final Function<String, Particle> particleResolver;
    private final BlockCodec blockCodec;
    private final Supplier<UUID> boundUuid;
    private final Function<UUID, Player> onlinePlayers;

    static final String DUST_ID = "minecraft:dust";
    static final String BLOCK_ID = "minecraft:block";
    private static final Set<String> SPEC_FIELDS = Set.of("particle_id", "receiver", "data");
    private static final Set<String> DUST_FIELDS = Set.of("color", "size");
    private static final BigDecimal MIN_DUST_SIZE = new BigDecimal("0.01");
    private static final BigDecimal MAX_DUST_SIZE = new BigDecimal("4.0");

    WorldB5Commands(
            WorldCommandContext session,
            EntityHandleRegistry handles,
            RuntimePolicy policy
    ) {
        this(session, handles, policy, WorldB5Commands::particle);
    }

    WorldB5Commands(
            WorldCommandContext session,
            EntityHandleRegistry handles,
            RuntimePolicy policy,
            Function<String, Particle> particleResolver
    ) {
        this(session, handles, policy, particleResolver, null, () -> null, Bukkit::getPlayer);
    }

    WorldB5Commands(
            WorldCommandContext session,
            EntityHandleRegistry handles,
            RuntimePolicy policy,
            Function<String, Particle> particleResolver,
            BlockCodec blockCodec,
            Supplier<UUID> boundUuid,
            Function<UUID, Player> onlinePlayers
    ) {
        this.session = session;
        this.handles = handles;
        this.policy = policy;
        this.particleResolver = particleResolver;
        this.blockCodec = blockCodec;
        this.boundUuid = boundUuid;
        this.onlinePlayers = onlinePlayers;
    }

    void handleGetHeight(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 2, 3);
            int relativeX = WireParams.integer(args, 0);
            int relativeZ = WireParams.integer(args, 1);
            Location origin = requireOrigin();
            World world = origin.getWorld();
            int absoluteX = Math.addExact(origin.getBlockX(), relativeX);
            int absoluteZ = Math.addExact(origin.getBlockZ(), relativeZ);
            Location location = new Location(world, absoluteX, origin.getY(), absoluteZ);
            if (!preflightLocation(location)) {
                return;
            }
            int worldMinY = world.getMinHeight();
            int worldMaxY = world.getMaxHeight() - 1;
            int startY = worldMaxY;
            if (args.size() == 3) {
                int relativeMaxY = WireParams.integer(args, 2);
                long requested = (long) origin.getBlockY() + relativeMaxY;
                startY = requested > worldMaxY ? worldMaxY
                        : requested < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) requested;
            }
            int scanUnits = startY < worldMinY ? 0 : startY - worldMinY + 1;
            if (!admit(scanUnits)) {
                return;
            }
            if (!prepareChunk(location)) {
                return;
            }
            OptionalInt found = MiscCommands.findHighestExposedBlockY(
                    worldMinY,
                    worldMaxY,
                    startY,
                    y -> world.getBlockAt(absoluteX, y, absoluteZ).isPassable());
            if (found.isEmpty()) {
                session.respondError(-32000, "height_not_found", null);
                return;
            }
            session.respondResult(found.getAsInt() - origin.getBlockY());
        } catch (ArithmeticException | IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        }
    }

    /**
     * Params: [x,y,z,offset_x,offset_y,offset_z,particle,speed,count,(force)]. {@code particle} is a
     * data-less particle ID string or a protocol 23.2 {@code ParticleSpec} object
     * （wire §5.8.3、DECISIONS 2026-09-23-01／2026-09-30-01）。Validation and side effects run in the
     * contract order: params → count policy → particle shape → spec → ID → data → receiver →
     * permission／build range → work → chunk → spawn.
     */
    void handleSpawnParticle(JsonElement params) {
        try {
            // (1) 9／10 params and scalars
            JsonArray args = WireParams.positional(params, 9, 10);
            Location location = relativeLocation(args, 0);
            double offsetX = nonNegative(WireParams.finiteDouble(args, 3));
            double offsetY = nonNegative(WireParams.finiteDouble(args, 4));
            double offsetZ = nonNegative(WireParams.finiteDouble(args, 5));
            double speed = nonNegative(WireParams.finiteDouble(args, 7));
            int count = WireParams.integer(args, 8);
            boolean force = args.size() == 10 ? WireParams.bool(args, 9) : true;
            if (count < 0) {
                throw new IllegalArgumentException("particle count must be non-negative");
            }
            // (2) count runtime policy
            if (count > policy.maxParticleCount()) {
                session.respondError(-32000, "work_limit_exceeded", null);
                return;
            }
            // (3)(4) particle string／object shape, ParticleSpec top-level and particle_id
            JsonElement particleArg = args.get(6);
            JsonObject spec = null;
            String id;
            if (particleArg != null && particleArg.isJsonObject()) {
                spec = particleArg.getAsJsonObject();
                for (String key : spec.keySet()) {
                    if (!SPEC_FIELDS.contains(key)) {
                        throw new IllegalArgumentException("unknown ParticleSpec field");
                    }
                }
                id = stringField(spec, "particle_id");
                if (spec.has("data") && spec.get("data").isJsonNull()) {
                    throw new IllegalArgumentException("data must not be null");
                }
            } else {
                id = WireParams.string(args, 6);
            }
            // (5) particle ID
            Particle particle = particleResolver.apply(id);
            if (particle == null) {
                session.respondError(-32602, "unknown_particle", null);
                return;
            }
            // (6) data presence, type and schema
            Object data;
            try {
                data = particleData(particle, spec == null ? null : spec.get("data"));
            } catch (ParticleDataException e) {
                session.respondError(-32602, e.reason, e.data);
                return;
            }
            // (7) receiver syntax and self player
            Player receiver = null;
            if (spec != null && spec.has("receiver")) {
                String mode = stringField(spec, "receiver");
                if ("self".equals(mode)) {
                    UUID player = boundUuid.get();
                    if (player == null) {
                        session.respondError(-32000, "auth_required", null);
                        return;
                    }
                    receiver = onlinePlayers.apply(player);
                    if (receiver == null || !receiver.isOnline()) {
                        session.respondError(-32000, "player_offline", null);
                        return;
                    }
                } else if (!"world".equals(mode)) {
                    throw new IllegalArgumentException("receiver must be world or self");
                }
            }
            // (8) permission／build range, (9) work, (10) chunk
            if (!preflightLocation(location) || !admit(count) || !prepareChunk(location)) {
                return;
            }
            // (11) spawn
            ParticleBuilder builder = particleBuilder(
                    particle, location, count, offsetX, offsetY, offsetZ, speed, force).data(data);
            if (receiver != null) {
                builder.receivers(receiver);
            }
            builder.spawn();
            session.respondResult(count);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        } catch (Exception e) {
            session.respondError(-32000, "internal_error", null);
        }
    }

    /**
     * Resolves typed data. Only minecraft:dust and minecraft:block accept object data in B8; object
     * data for any other particle is particle_data_unsupported. Missing required data is
     * particle_data_required.
     */
    private Object particleData(Particle particle, JsonElement data) throws ParticleDataException {
        String key = particle.getKey().toString();
        boolean needsData = particle.getDataType() != Void.class;
        if (data == null) {
            if (needsData) {
                throw new ParticleDataException("particle_data_required", null);
            }
            return null;
        }
        if (!DUST_ID.equals(key) && !BLOCK_ID.equals(key)) {
            if (data.isJsonObject()) {
                throw new ParticleDataException("particle_data_unsupported", null);
            }
            throw new IllegalArgumentException("particle data must be an object");
        }
        if (!data.isJsonObject()) {
            throw new IllegalArgumentException("particle data must be an object");
        }
        if (DUST_ID.equals(key)) {
            return dustOptions(data.getAsJsonObject());
        }
        if (blockCodec == null) {
            throw new IllegalStateException("block particle data is not available");
        }
        try {
            return blockCodec.decode(data, "params[6].data");
        } catch (BlockCodec.ValidationException e) {
            throw new ParticleDataException(e.reason, e.data);
        }
    }

    /** Dust data is exactly {"color":[R,G,B],"size":number}; RGB 0..255 integers, size 0.01..4.0. */
    static Particle.DustOptions dustOptions(JsonObject data) {
        if (!data.keySet().equals(DUST_FIELDS)) {
            throw new IllegalArgumentException("dust data must have exactly color and size");
        }
        JsonElement color = data.get("color");
        if (color == null || !color.isJsonArray() || color.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException("color must be [R,G,B]");
        }
        int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            rgb[i] = WireParams.integer(color.getAsJsonArray(), i);
            if (rgb[i] < 0 || rgb[i] > 255) {
                throw new IllegalArgumentException("color channel must be 0..255");
            }
        }
        JsonElement size = data.get("size");
        if (size == null || !size.isJsonPrimitive() || !size.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("size must be a number");
        }
        BigDecimal exactSize = new BigDecimal(size.getAsJsonPrimitive().getAsString());
        if (exactSize.compareTo(MIN_DUST_SIZE) < 0 || exactSize.compareTo(MAX_DUST_SIZE) > 0) {
            throw new IllegalArgumentException("size must be within 0.01..4.0");
        }
        return new Particle.DustOptions(Color.fromRGB(rgb[0], rgb[1], rgb[2]), exactSize.floatValue());
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }

    private static final class ParticleDataException extends Exception {
        final String reason;
        final java.util.Map<String, Object> data;

        ParticleDataException(String reason, java.util.Map<String, Object> data) {
            super(reason, null, false, false);
            this.reason = reason;
            this.data = data;
        }
    }

    /** Params: [x,y,z,entity_id]. */
    void handleSpawnEntity(JsonElement params) {
        EntityHandleRegistry.Reservation reservation = null;
        Entity spawned = null;
        try {
            JsonArray args = WireParams.positional(params, 4);
            Location location = relativeLocation(args, 0);
            String id = WireParams.string(args, 3);
            EntityType type = entityType(id);
            if (type == null) {
                session.respondError(-32602, "unknown_entity", null);
                return;
            }
            if (!type.isSpawnable() || type == EntityType.PLAYER || type.getEntityClass() == null
                    || Player.class.isAssignableFrom(type.getEntityClass())) {
                session.respondError(-32602, "entity_not_spawnable", null);
                return;
            }
            if (!preflightLocation(location) || !admit(1)) {
                return;
            }
            try {
                reservation = handles.reserve();
            } catch (EntityHandleRegistry.CapacityException e) {
                session.respondError(-32000, "entity_capacity_exhausted", null);
                return;
            }
            if (!prepareChunk(location)) {
                return;
            }
            spawned = location.getWorld().spawnEntity(location, type);
            String handle = reservation.commit(spawned);
            reservation = null;
            session.respondResult(handle);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        } catch (Exception e) {
            if (spawned != null && spawned.isValid()) {
                spawned.remove();
            }
            session.respondError(-32000, "entity_spawn_failed", null);
        } finally {
            if (reservation != null) {
                reservation.close();
            }
        }
    }

    private Location relativeLocation(JsonArray args, int offset) {
        Location origin = requireOrigin();
        double x = origin.getX() + WireParams.finiteDouble(args, offset);
        double y = origin.getY() + WireParams.finiteDouble(args, offset + 1);
        double z = origin.getZ() + WireParams.finiteDouble(args, offset + 2);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("absolute coordinates must be finite");
        }
        return new Location(origin.getWorld(), x, y, z);
    }

    private boolean preflightLocation(Location location) {
        if (!session.hasConstructionPermission()) {
            session.respondError(-32000, "permission_denied", null);
            return false;
        }
        if (!session.isWithinBuildRange(location)) {
            session.respondError(-32000, "build_denied", null);
            return false;
        }
        return true;
    }

    private boolean prepareChunk(Location location) {
        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!ensureChunkLoaded(world, chunkX, chunkZ)) {
            session.respondError(-32000, "backpressure", null);
            return false;
        }
        return true;
    }

    static boolean ensureChunkLoaded(World world, int chunkX, int chunkZ) {
        return world.isChunkLoaded(chunkX, chunkZ)
                || world.loadChunk(chunkX, chunkZ, true);
    }

    /**
     * ParticleBuilder mapping. Receivers and source stay unset so delivery is world-wide unless the
     * caller narrows receivers; data starts null and is set only for typed data.
     */
    static ParticleBuilder particleBuilder(
            Particle particle,
            Location location,
            int count,
            double offsetX,
            double offsetY,
            double offsetZ,
            double speed,
            boolean force
    ) {
        return particle.builder()
                .location(location)
                .count(count)
                .offset(offsetX, offsetY, offsetZ)
                .extra(speed)
                .data(null)
                .force(force);
    }

    private boolean admit(int units) {
        WorkAdmission.Result result = session.admitWork(units);
        if (result == WorkAdmission.Result.ACCEPTED) {
            return true;
        }
        session.respondError(-32000,
                result == WorkAdmission.Result.BACKPRESSURE
                        ? "backpressure" : "work_limit_exceeded",
                null);
        return false;
    }

    private Location requireOrigin() {
        Location origin = session.getOrigin();
        if (origin == null || origin.getWorld() == null) {
            throw new IllegalArgumentException("origin is not set");
        }
        return origin;
    }

    static Particle particle(String raw) {
        NamespacedKey key = canonicalKey(raw);
        return key == null ? null : Registry.PARTICLE_TYPE.get(key);
    }

    private static EntityType entityType(String raw) {
        NamespacedKey key = canonicalKey(raw);
        return key == null ? null : Registry.ENTITY_TYPE.get(key);
    }

    private static NamespacedKey canonicalKey(String raw) {
        NamespacedKey key = NamespacedKey.fromString(raw);
        return key != null && raw.equals(key.toString()) ? key : null;
    }

    private static double nonNegative(double value) {
        if (value < 0.0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        return value;
    }
}
