package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Protocol 23.2 entity lifecycle slice: world.getNearbyEntities, entity.getPose／setPose／remove
 * （wire §5.8.3、DECISIONS 2026-09-23-01／2026-09-30-01）。
 */
final class EntityLifecycleCommands {
    private final B7CommandContext session;
    private final EntityHandleRegistry handles;
    private final DimensionResolver dimensions;
    private final RuntimePolicy policy;
    private final BiFunction<Location, Double, Collection<Entity>> nearbySearch;

    EntityLifecycleCommands(
            B7CommandContext session,
            EntityHandleRegistry handles,
            DimensionResolver dimensions,
            RuntimePolicy policy
    ) {
        this(session, handles, dimensions, policy, EntityLifecycleCommands::searchLoadedBox);
    }

    EntityLifecycleCommands(
            B7CommandContext session,
            EntityHandleRegistry handles,
            DimensionResolver dimensions,
            RuntimePolicy policy,
            BiFunction<Location, Double, Collection<Entity>> nearbySearch
    ) {
        this.session = session;
        this.handles = handles;
        this.dimensions = dimensions;
        this.policy = policy;
        this.nearbySearch = nearbySearch;
    }

    void register(CommandRegistry registry) {
        registry.registerStructured("world.getNearbyEntities", this::handleGetNearbyEntities);
        registry.registerStructured("entity.getPose", this::handleGetPose);
        registry.registerStructured("entity.setPose", this::handleSetPose);
        registry.registerStructured("entity.remove", this::handleRemove, false);
    }

    /** Params: [x, y, z, radius, max_entities]. */
    void handleGetNearbyEntities(JsonElement params) {
        NearbyQuery query;
        try {
            query = parseNearby(params);
        } catch (IllegalArgumentException | ArithmeticException e) {
            invalidParams();
            return;
        }
        if (!session.hasConstructionPermission()) {
            session.respondError(-32000, "permission_denied", null);
            return;
        }
        World world = query.center().getWorld();
        Location low = new Location(world, query.center().getX() - query.radius(),
                query.center().getY(), query.center().getZ() - query.radius());
        Location high = new Location(world, query.center().getX() + query.radius(),
                query.center().getY(), query.center().getZ() + query.radius());
        if (!session.isWithinBuildRange(low) || !session.isWithinBuildRange(high)) {
            session.respondError(-32000, "build_denied", null);
            return;
        }
        long cost;
        try {
            cost = workCost(query);
        } catch (ArithmeticException e) {
            session.respondError(-32000, "work_limit_exceeded", null);
            return;
        }
        if (!session.admitSetterWork(cost)) {
            return;
        }
        List<Entity> selected = select(query, nearbySearch.apply(query.center(), query.radius()));
        List<EntityHandleRegistry.Issued> issued;
        try {
            issued = handles.issueAll(selected);
        } catch (EntityHandleRegistry.CapacityException e) {
            session.respondError(-32000, "entity_capacity_exhausted", null);
            return;
        }
        Location origin = session.getOrigin();
        List<Map<String, Object>> result = new ArrayList<>(issued.size());
        for (EntityHandleRegistry.Issued entry : issued) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("handle", entry.handle());
            item.put("type", entry.entity().getType().getKey().toString());
            item.put("pos", PlayerCommands.positionResult(entry.entity().getLocation(), origin).get("pos"));
            result.add(item);
        }
        session.respondResult(result);
    }

    /** Params: [handle]. Work cost 0. */
    void handleGetPose(JsonElement params) {
        String handle;
        try {
            handle = WireParams.string(WireParams.positional(params, 1), 0);
        } catch (IllegalArgumentException e) {
            invalidParams();
            return;
        }
        if (!requirePermission()) {
            return;
        }
        Entity entity = resolve(handle);
        if (entity == null) {
            return;
        }
        Location origin = session.getOrigin();
        if (origin == null) {
            session.respondError(-32000, "origin_not_set", null);
            return;
        }
        session.respondResult(PlayerCommands.poseResult(entity.getLocation(), origin));
    }

    /** Params: [handle, dimension_ref, x, y, z, yaw, pitch]. Work cost 1. */
    void handleSetPose(JsonElement params) {
        String handle;
        String dimensionRef;
        double relativeX;
        double relativeY;
        double relativeZ;
        double yaw;
        double pitch;
        try {
            JsonArray args = WireParams.positional(params, 7);
            handle = WireParams.string(args, 0);
            dimensionRef = WireParams.string(args, 1);
            DimensionResolver.parse(dimensionRef);
            relativeX = WireParams.finiteDouble(args, 2);
            relativeY = WireParams.finiteDouble(args, 3);
            relativeZ = WireParams.finiteDouble(args, 4);
            yaw = WireParams.finiteDouble(args, 5);
            pitch = WireParams.finiteDouble(args, 6);
            WireNumbers.requirePitch(pitch);
        } catch (IllegalArgumentException e) {
            invalidParams();
            return;
        }
        if (!requirePermission()) {
            return;
        }
        Entity entity = resolve(handle);
        if (entity == null) {
            return;
        }
        DimensionResolver.ResolvedDimension resolved = dimensions.resolve(dimensionRef);
        if (!resolved.isLoaded()) {
            session.respondError(-32000, "unknown_dimension",
                    BuildStateCommands.dimensionData(resolved.canonicalKey()));
            return;
        }
        Location origin = session.getOrigin();
        if (origin == null) {
            session.respondError(-32000, "origin_not_set", null);
            return;
        }
        double x = origin.getX() + relativeX;
        double y = origin.getY() + relativeY;
        double z = origin.getZ() + relativeZ;
        if (!PlayerCommands.areFinite(x, y, z)) {
            invalidParams();
            return;
        }
        // Paper stores angles as float; periodic normalization keeps finite input within range.
        Location target = new Location(resolved.world(), x, y, z,
                (float) WireNumbers.normalizeYaw(yaw), (float) pitch);
        if (!session.isWithinBuildRange(target)) {
            session.respondError(-32000, "build_denied", null);
            return;
        }
        if (!session.admitSetterWork(1)) {
            return;
        }
        try {
            if (!entity.teleport(target)) {
                session.respondError(-32000, "teleport_failed", null);
                return;
            }
            handles.updateDimension(handle, entity);
            session.respondResult(PlayerCommands.poseResult(entity.getLocation(), origin));
        } catch (Exception e) {
            session.respondError(-32000, "internal_error", null);
        }
    }

    /** Params: [handle]. Work cost 1. Success result is null and the handle is revoked at once. */
    void handleRemove(JsonElement params) {
        String handle;
        try {
            handle = WireParams.string(WireParams.positional(params, 1), 0);
        } catch (IllegalArgumentException e) {
            invalidParams();
            return;
        }
        if (!requirePermission()) {
            return;
        }
        Entity entity = resolve(handle);
        if (entity == null || !session.admitSetterWork(1)) {
            return;
        }
        try {
            entity.remove();
            handles.invalidate(handle);
            session.respondResult(null);
        } catch (Exception e) {
            session.respondError(-32000, "internal_error", null);
        }
    }

    record NearbyQuery(Location center, double radius, int maxEntities) {
    }

    private NearbyQuery parseNearby(JsonElement params) {
        JsonArray args = WireParams.positional(params, 5);
        double relativeX = WireParams.finiteDouble(args, 0);
        double relativeY = WireParams.finiteDouble(args, 1);
        double relativeZ = WireParams.finiteDouble(args, 2);
        double radius = WireParams.finiteDouble(args, 3);
        int maxEntities = WireParams.integer(args, 4);
        if (radius < 0.0 || radius > policy.nearbyMaxRadius()) {
            throw new IllegalArgumentException("radius is outside the runtime policy");
        }
        if (maxEntities < 1 || maxEntities > policy.nearbyMaxEntities()) {
            throw new IllegalArgumentException("max_entities is outside the runtime policy");
        }
        Location origin = session.getOrigin();
        if (origin == null || origin.getWorld() == null) {
            throw new IllegalArgumentException("origin is not set");
        }
        double x = origin.getX() + relativeX;
        double y = origin.getY() + relativeY;
        double z = origin.getZ() + relativeZ;
        if (!PlayerCommands.areFinite(x, y, z)) {
            throw new IllegalArgumentException("absolute coordinates must be finite");
        }
        // Chunk indices must be representable; otherwise the query is invalid, not over budget.
        chunkIndex(x - radius);
        chunkIndex(x + radius);
        chunkIndex(z - radius);
        chunkIndex(z + radius);
        return new NearbyQuery(new Location(origin.getWorld(), x, y, z), radius, maxEntities);
    }

    static long workCost(NearbyQuery query) {
        long minChunkX = chunkIndex(query.center().getX() - query.radius());
        long maxChunkX = chunkIndex(query.center().getX() + query.radius());
        long minChunkZ = chunkIndex(query.center().getZ() - query.radius());
        long maxChunkZ = chunkIndex(query.center().getZ() + query.radius());
        long columns = Math.multiplyExact(
                Math.addExact(Math.subtractExact(maxChunkX, minChunkX), 1),
                Math.addExact(Math.subtractExact(maxChunkZ, minChunkZ), 1));
        return Math.addExact(columns, query.maxEntities());
    }

    /** floorDiv(floor(value), 16), rejecting values whose block index does not fit an int. */
    static long chunkIndex(double value) {
        double floored = Math.floor(value);
        if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("coordinate cannot be mapped to a chunk index");
        }
        return Math.floorDiv((long) floored, 16L);
    }

    /** filter → UUID dedup → sort by squared distance then UUID string → truncate. */
    static List<Entity> select(NearbyQuery query, Collection<Entity> candidates) {
        Location center = query.center();
        double radiusSquared = query.radius() * query.radius();
        Map<UUID, Candidate> unique = new LinkedHashMap<>();
        for (Entity entity : candidates) {
            if (entity == null || entity instanceof Player
                    || entity.isDead() || !entity.isValid() || !entity.isInWorld()) {
                continue;
            }
            Location location = entity.getLocation();
            if (location.getWorld() == null || !location.getWorld().equals(center.getWorld())) {
                continue;
            }
            double dx = location.getX() - center.getX();
            double dy = location.getY() - center.getY();
            double dz = location.getZ() - center.getZ();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared <= radiusSquared) {
                unique.putIfAbsent(entity.getUniqueId(), new Candidate(entity, distanceSquared));
            }
        }
        // UUID#toString is canonical lowercase hex, so String order equals code point order here.
        return unique.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::distanceSquared)
                        .thenComparing(candidate -> candidate.entity().getUniqueId().toString()))
                .limit(query.maxEntities())
                .map(Candidate::entity)
                .toList();
    }

    private record Candidate(Entity entity, double distanceSquared) {
    }

    private static Collection<Entity> searchLoadedBox(Location center, Double radius) {
        // Paper returns entities from loaded chunks only; the query never loads chunks.
        return center.getWorld().getNearbyEntities(center, radius, radius, radius);
    }

    private boolean requirePermission() {
        if (session.hasConstructionPermission()) {
            return true;
        }
        session.respondError(-32000, "permission_denied", null);
        return false;
    }

    private Entity resolve(String handle) {
        EntityHandleRegistry.ResolveResult resolved = handles.resolve(handle);
        return switch (resolved.status()) {
            case ACTIVE -> resolved.entity();
            case NOT_FOUND -> {
                session.respondError(-32000, "entity_not_found", null);
                yield null;
            }
            case REMOVED_OR_UNLOADED -> {
                session.respondError(-32000, "entity_unavailable", null);
                yield null;
            }
            case DIMENSION_CHANGED -> {
                session.respondError(-32000, "entity_dimension_changed", null);
                yield null;
            }
        };
    }

    private void invalidParams() {
        session.respondError(-32602, "invalid_params", null);
    }
}
