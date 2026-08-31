package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.OptionalInt;

/** b5 world query/spawn commands with validation before any world mutation. */
final class WorldB5Commands {
    private final RemoteSession session;
    private final EntityHandleRegistry handles;
    private final B5RuntimePolicy policy;

    WorldB5Commands(
            RemoteSession session,
            EntityHandleRegistry handles,
            B5RuntimePolicy policy
    ) {
        this.session = session;
        this.handles = handles;
        this.policy = policy;
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

    /** Params: [x,y,z,offset_x,offset_y,offset_z,particle_id,speed,count,(force)]. */
    void handleSpawnParticle(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 9, 10);
            Location location = relativeLocation(args, 0);
            double offsetX = nonNegative(WireParams.finiteDouble(args, 3));
            double offsetY = nonNegative(WireParams.finiteDouble(args, 4));
            double offsetZ = nonNegative(WireParams.finiteDouble(args, 5));
            String id = WireParams.string(args, 6);
            double speed = nonNegative(WireParams.finiteDouble(args, 7));
            int count = WireParams.integer(args, 8);
            boolean force = args.size() == 10 ? WireParams.bool(args, 9) : true;
            if (count < 0) {
                throw new IllegalArgumentException("particle count must be non-negative");
            }
            if (count > policy.maxParticleCount()) {
                session.respondError(-32000, "work_limit_exceeded", null);
                return;
            }
            Particle particle = particle(id);
            if (particle == null) {
                session.respondError(-32602, "unknown_particle", null);
                return;
            }
            if (particle.getDataType() != Void.class) {
                session.respondError(-32602, "particle_data_required", null);
                return;
            }
            if (!preflightLocation(location) || !admit(count) || !prepareChunk(location)) {
                return;
            }
            particleBuilder(
                    particle, location, count, offsetX, offsetY, offsetZ, speed, force).spawn();
            session.respondResult(count);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        } catch (Exception e) {
            session.respondError(-32000, "internal_error", null);
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
     * b7 ParticleBuilder Stage 1 mapping. Receivers and source stay unset so delivery remains
     * world-wide, and null data preserves the existing no-data particle contract.
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

    private static Particle particle(String raw) {
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
