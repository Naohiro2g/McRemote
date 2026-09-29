package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol 23.2 entity lifecycle（wire §5.8.3、DECISIONS 2026-09-23-01／2026-09-30-01）。 */
class EntityLifecycleCommandsTest {
    private static final World OVERWORLD = world("overworld");
    private static final World NETHER = world("the_nether");

    // ---- issueAll transaction ----

    @Test
    void issueAllReusesSameDimensionHandleAndIssuesNewOnesInOrder() {
        Harness h = new Harness(4);
        FakeEntity known = h.entity(uuid("a"), OVERWORLD, 1, 0, 0);
        String existing = h.handles.issue(known.entity);
        FakeEntity fresh = h.entity(uuid("b"), OVERWORLD, 2, 0, 0);

        List<EntityHandleRegistry.Issued> issued = h.handles.issueAll(List.of(fresh.entity, known.entity));

        assertEquals(2, issued.size());
        assertTrue(issued.get(0).handle().startsWith(EntityHandleRegistry.PREFIX));
        assertEquals(existing, issued.get(1).handle());
        assertEquals(2, h.handles.size());
    }

    @Test
    void issueAllReplacesHandleIssuedInAnotherDimensionInOneTransaction() {
        Harness h = new Harness(4);
        FakeEntity moved = h.entity(uuid("a"), OVERWORLD, 1, 0, 0);
        String old = h.handles.issue(moved.entity);
        moved.location.setWorld(NETHER);

        List<EntityHandleRegistry.Issued> issued = h.handles.issueAll(List.of(moved.entity));

        assertNotEquals(old, issued.get(0).handle());
        assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND, h.handles.resolve(old).status());
        assertEquals(EntityHandleRegistry.ResolveStatus.ACTIVE,
                h.handles.resolve(issued.get(0).handle()).status());
        assertEquals(1, h.handles.size());
    }

    @Test
    void issueAllCapacityFailureChangesNothingIncludingStagedInvalidations() {
        Harness h = new Harness(2);
        FakeEntity moved = h.entity(uuid("a"), OVERWORLD, 1, 0, 0);
        String old = h.handles.issue(moved.entity);
        moved.location.setWorld(NETHER);
        FakeEntity other = h.entity(uuid("b"), NETHER, 2, 0, 0);
        FakeEntity third = h.entity(uuid("c"), NETHER, 3, 0, 0);

        // projected = 1 - 1 + 3 = 3 > 2
        assertThrows(EntityHandleRegistry.CapacityException.class,
                () -> h.handles.issueAll(List.of(moved.entity, other.entity, third.entity)));
        assertEquals(1, h.handles.size());
        moved.location.setWorld(OVERWORLD);
        assertEquals(EntityHandleRegistry.ResolveStatus.ACTIVE, h.handles.resolve(old).status());
    }

    @Test
    void issueAllCountsStagedInvalidationsAndOpenReservationsInProjectedCapacity() {
        Harness h = new Harness(2);
        FakeEntity gone = h.entity(uuid("a"), OVERWORLD, 1, 0, 0);
        String old = h.handles.issue(gone.entity);
        gone.dead.set(true);
        FakeEntity fresh = h.entity(uuid("b"), OVERWORLD, 2, 0, 0);
        EntityHandleRegistry.Reservation open = h.handles.reserve();

        // projected = 1 - 1 + 1 + 1 reservation = 2 <= 2
        List<EntityHandleRegistry.Issued> issued = h.handles.issueAll(List.of(gone.entity, fresh.entity));

        assertEquals(1, issued.size(), "removed candidate is excluded without refill");
        assertEquals(fresh.entity, issued.get(0).entity());
        assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND, h.handles.resolve(old).status());
        open.close();
    }

    // ---- world.getNearbyEntities ----

    @Test
    void nearbyUsesInclusiveSphereAndExcludesPlayersOtherDimensionsAndDuplicates() {
        Harness h = new Harness(8);
        FakeEntity edge = h.entity(uuid("1"), OVERWORLD, 3, 0, 0);          // exactly r
        FakeEntity outside = h.entity(uuid("2"), OVERWORLD, 2.2, 2.2, 0);   // inside box, outside sphere
        FakeEntity nether = h.entity(uuid("3"), NETHER, 1, 0, 0);
        Player player = player(new Location(OVERWORLD, 1, 0, 0));
        h.search.addAll(List.of(edge.entity, outside.entity, nether.entity, player, edge.entity));

        h.commands.handleGetNearbyEntities(params("[0,0,0,3,8]"));

        List<?> result = (List<?>) h.context.result;
        assertEquals(1, result.size());
        assertEquals("minecraft:cow", ((Map<?, ?>) result.get(0)).get("type"));
    }

    @Test
    void nearbySortsByDistanceThenCanonicalUuidStringAndTruncatesWithoutRefill() {
        Harness h = new Harness(8);
        // UUID.compareTo is signed and would put 8000... before 1000...; the contract uses the string.
        FakeEntity high = h.entity(UUID.fromString("80000000-0000-0000-0000-000000000000"), OVERWORLD, 1, 0, 0);
        FakeEntity low = h.entity(UUID.fromString("10000000-0000-0000-0000-000000000000"), OVERWORLD, 0, 1, 0);
        FakeEntity nearest = h.entity(uuid("f"), OVERWORLD, 0.5, 0, 0);
        FakeEntity far = h.entity(uuid("0"), OVERWORLD, 2, 0, 0);
        h.search.addAll(List.of(far.entity, high.entity, low.entity, nearest.entity));

        h.commands.handleGetNearbyEntities(params("[0,0,0,4,3]"));

        List<?> result = (List<?>) h.context.result;
        assertEquals(3, result.size());
        assertEquals(List.of(nearest.entity, low.entity, high.entity),
                List.of(h.resolved(result.get(0)), h.resolved(result.get(1)), h.resolved(result.get(2))));
    }

    @Test
    void nearbyResultIsHandleCanonicalTypeAndOriginRelativePosition() {
        Harness h = new Harness(8);
        FakeEntity cow = h.entity(uuid("1"), OVERWORLD, 1.25, 6, -0.5);
        h.search.add(cow.entity);

        h.commands.handleGetNearbyEntities(params("[1,6,0,5,1]"));

        Map<?, ?> item = (Map<?, ?>) ((List<?>) h.context.result).get(0);
        assertEquals(List.of("handle", "type", "pos"), new ArrayList<>(item.keySet()));
        assertTrue(((String) item.get("handle")).startsWith(EntityHandleRegistry.PREFIX));
        assertEquals(List.of(1.25, 6.0, -0.5), doubles(item.get("pos")));
    }

    @Test
    void nearbyEmptyResultIsEmptyList() {
        Harness h = new Harness(8);

        h.commands.handleGetNearbyEntities(params("[0,0,0,0,1]"));

        assertEquals(List.of(), h.context.result);
    }

    @Test
    void nearbyParamsAndPolicyViolationsAreInvalidBeforePermissionOrWork() {
        for (String bad : List.of("[0,0,0,65,1]", "[0,0,0,-1,1]", "[0,0,0,1,0]", "[0,0,0,1,65]",
                "[0,0,0,1,1.5]", "[0,0,0,1]", "[0,0,0,1,1,1]", "[\"0\",0,0,1,1]", "[0,0,1e308,64,1]")) {
            Harness h = new Harness(8);
            h.commands.handleGetNearbyEntities(params(bad));
            assertEquals("invalid_params", h.context.reason, bad);
            assertEquals(0, h.context.constructionChecks, bad);
            assertEquals(0, h.context.setterWorkCalls, bad);
        }
        Harness lowered = new Harness(8, 10, 5);
        lowered.commands.handleGetNearbyEntities(params("[0,0,0,11,1]"));
        assertEquals("invalid_params", lowered.context.reason, "runtime policy is not truncated");
        lowered.context.reason = null;
        lowered.commands.handleGetNearbyEntities(params("[0,0,0,10,6]"));
        assertEquals("invalid_params", lowered.context.reason);
    }

    @Test
    void nearbyBuildRangeCoversTheWholeBoundingSquareBeforeWork() {
        Harness h = new Harness(8);
        h.context.buildRange = 10;

        h.commands.handleGetNearbyEntities(params("[6,0,0,5,1]"));   // x reaches 11

        assertEquals("build_denied", h.context.reason);
        assertEquals(0, h.context.setterWorkCalls);
        assertEquals(0, h.searches.get());
    }

    @Test
    void nearbyWorkCostIsChunkColumnsPlusMaxEntitiesAndRejectionSkipsSearch() {
        Harness h = new Harness(8);
        h.context.setterAllowed = false;

        h.commands.handleGetNearbyEntities(params("[0,0,0,64,64]"));

        assertEquals(145, h.context.lastSetterUnits, "radius 64 spans 9x9 columns");
        assertEquals(0, h.searches.get());
        assertEquals(0, h.handles.size());

        Location center = new Location(OVERWORLD, 0.5, 0, 0.5);
        assertEquals(1 + 3, EntityLifecycleCommands.workCost(
                new EntityLifecycleCommands.NearbyQuery(center, 0, 3)));
        assertEquals(4 + 1, EntityLifecycleCommands.workCost(
                new EntityLifecycleCommands.NearbyQuery(new Location(OVERWORLD, 16, 0, 16), 0.5, 1)));
    }

    @Test
    void nearbyCapacityFailureReportsExhaustion() {
        Harness h = new Harness(1);
        h.search.add(h.entity(uuid("1"), OVERWORLD, 1, 0, 0).entity);
        h.search.add(h.entity(uuid("2"), OVERWORLD, 2, 0, 0).entity);

        h.commands.handleGetNearbyEntities(params("[0,0,0,5,2]"));

        assertEquals("entity_capacity_exhausted", h.context.reason);
        assertEquals(0, h.handles.size());
    }

    // ---- entity.getPose／setPose／remove ----

    @Test
    void getPoseReturnsDimensionPosYawPitchWithoutWork() {
        Harness h = new Harness(4);
        FakeEntity cow = h.entity(uuid("1"), OVERWORLD, 102, 64, 100);
        cow.location.setYaw(45f);
        cow.location.setPitch(-10f);
        String handle = h.handles.issue(cow.entity);

        h.commands.handleGetPose(params("[\"" + handle + "\"]"));

        Map<?, ?> pose = (Map<?, ?>) h.context.result;
        assertEquals(List.of("dimension", "pos", "yaw", "pitch"), new ArrayList<>(pose.keySet()));
        assertEquals("minecraft:overworld", pose.get("dimension"));
        assertEquals(0, h.context.setterWorkCalls);
    }

    @Test
    void setPoseTeleportsOnceUsesOneUnitAndReturnsReReadPose() {
        Harness h = new Harness(4);
        FakeEntity cow = h.entity(uuid("1"), OVERWORLD, 100, 0, 100);
        String handle = h.handles.issue(cow.entity);

        h.commands.handleSetPose(params("[\"" + handle + "\",\"overworld\",5,6,7,90,30]"));

        assertEquals(1, cow.teleports.get());
        assertEquals(1, h.context.lastSetterUnits);
        Map<?, ?> pose = (Map<?, ?>) h.context.result;
        assertEquals(List.of(5.0, 6.0, 7.0), doubles(pose.get("pos")));
    }

    @Test
    void setPoseIntoAnotherDimensionKeepsTheSameHandleUsable() {
        Harness h = new Harness(4);
        FakeEntity cow = h.entity(uuid("1"), OVERWORLD, 100, 0, 100);
        String handle = h.handles.issue(cow.entity);

        h.commands.handleSetPose(params("[\"" + handle + "\",\"the_nether\",0,0,0,0,0]"));

        assertEquals("minecraft:the_nether", ((Map<?, ?>) h.context.result).get("dimension"));
        assertEquals(EntityHandleRegistry.ResolveStatus.ACTIVE, h.handles.resolve(handle).status());
    }

    @Test
    void setPoseOrderIsInputPermissionHandleDimensionBuildRangeWorkPaper() {
        Harness h = new Harness(4);
        FakeEntity cow = h.entity(uuid("1"), OVERWORLD, 100, 0, 100);
        String handle = h.handles.issue(cow.entity);
        String ok = "[\"" + handle + "\",\"overworld\",0,0,0,0,0]";

        h.commands.handleSetPose(params("[\"" + handle + "\",\"overworld\",0,0,0,0,91]"));
        assertEquals("invalid_params", h.context.reason);
        assertEquals(0, h.context.constructionChecks);

        h.context.constructionAllowed = false;
        h.commands.handleSetPose(params("[\"mcr_eh_missing\",\"overworld\",0,0,0,0,0]"));
        assertEquals("permission_denied", h.context.reason);

        h.context.constructionAllowed = true;
        h.commands.handleSetPose(params("[\"mcr_eh_missing\",\"nowhere\",0,0,0,0,0]"));
        assertEquals("entity_not_found", h.context.reason);

        h.commands.handleSetPose(params("[\"" + handle + "\",\"nowhere\",0,0,0,0,0]"));
        assertEquals("unknown_dimension", h.context.reason);

        h.context.buildRange = 1;
        h.commands.handleSetPose(params("[\"" + handle + "\",\"overworld\",5,0,0,0,0]"));
        assertEquals("build_denied", h.context.reason);
        assertEquals(0, h.context.setterWorkCalls);

        h.context.buildRange = 1000;
        cow.teleportResult.set(false);
        h.commands.handleSetPose(params(ok));
        assertEquals("teleport_failed", h.context.reason);
        assertEquals(1, h.context.setterWorkCalls);
    }

    @Test
    void removeReturnsNullUsesOneUnitAndRevokesHandleAtOnce() {
        Harness h = new Harness(4);
        FakeEntity cow = h.entity(uuid("1"), OVERWORLD, 100, 0, 100);
        String handle = h.handles.issue(cow.entity);
        h.context.result = "unset";

        h.commands.handleRemove(params("[\"" + handle + "\"]"));

        assertNull(h.context.result);
        assertNull(h.context.reason);
        assertEquals(1, h.context.lastSetterUnits);
        assertTrue(cow.dead.get());
        assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND, h.handles.resolve(handle).status());
        h.commands.handleRemove(params("[\"" + handle + "\"]"));
        assertEquals("entity_not_found", h.context.reason);
    }

    @Test
    void handleFailureReasonsMatchB7EntityDirection() {
        Harness h = new Harness(4);
        FakeEntity cow = h.entity(uuid("1"), OVERWORLD, 100, 0, 100);
        String handle = h.handles.issue(cow.entity);
        cow.location.setWorld(NETHER);

        h.commands.handleGetPose(params("[\"" + handle + "\"]"));
        assertEquals("entity_dimension_changed", h.context.reason);

        FakeEntity gone = h.entity(uuid("2"), OVERWORLD, 100, 0, 100);
        String goneHandle = h.handles.issue(gone.entity);
        gone.dead.set(true);
        h.commands.handleRemove(params("[\"" + goneHandle + "\"]"));
        assertEquals("entity_unavailable", h.context.reason);
    }

    // ---- harness ----

    private static List<Double> doubles(Object values) {
        return ((List<?>) values).stream().map(value -> ((BigDecimal) value).doubleValue()).toList();
    }

    private static com.google.gson.JsonElement params(String json) {
        return JsonParser.parseString(json);
    }

    private static UUID uuid(String hexDigit) {
        return UUID.fromString(hexDigit.repeat(8) + "-0000-0000-0000-000000000000");
    }

    private static final class Harness {
        final Map<UUID, Entity> byId = new HashMap<>();
        final EntityHandleRegistry handles;
        final TestContext context = new TestContext(new Location(OVERWORLD, 100, 64, 100));
        final List<Entity> search = new ArrayList<>();
        final AtomicInteger searches = new AtomicInteger();
        final EntityLifecycleCommands commands;

        Harness(int capacity) {
            this(capacity, 64, 64);
        }

        Harness(int capacity, int maxRadius, int maxEntities) {
            handles = new EntityHandleRegistry(capacity, new SecureRandom(), byId::get);
            YamlConfiguration config = new YamlConfiguration();
            config.set("entities.nearby_max_radius", maxRadius);
            config.set("entities.nearby_max_entities", maxEntities);
            DimensionResolver dimensions = new DimensionResolver(key -> switch (key.toString()) {
                case "minecraft:overworld" -> OVERWORLD;
                case "minecraft:the_nether" -> NETHER;
                default -> null;
            });
            commands = new EntityLifecycleCommands(context, handles, dimensions,
                    RuntimePolicy.from(config), (center, radius) -> {
                        searches.incrementAndGet();
                        return new ArrayList<>(search);
                    });
        }

        /** Coordinates are relative to the stream origin (100, 64, 100). */
        FakeEntity entity(UUID id, World world, double x, double y, double z) {
            FakeEntity fake = new FakeEntity(id, new Location(world, 100 + x, 64 + y, 100 + z));
            byId.put(id, fake.entity);
            return fake;
        }

        Entity resolved(Object item) {
            return handles.resolve((String) ((Map<?, ?>) item).get("handle")).entity();
        }
    }

    private static final class FakeEntity {
        final Location location;
        final AtomicBoolean dead = new AtomicBoolean();
        final AtomicBoolean teleportResult = new AtomicBoolean(true);
        final AtomicInteger teleports = new AtomicInteger();
        final Entity entity;

        FakeEntity(UUID id, Location location) {
            this.location = location;
            this.entity = proxy(Entity.class, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getType" -> EntityType.COW;
                case "getWorld" -> location.getWorld();
                case "getLocation" -> location.clone();
                case "isValid", "isInWorld" -> !dead.get();
                case "isDead" -> dead.get();
                case "remove" -> {
                    dead.set(true);
                    yield null;
                }
                case "teleport" -> {
                    teleports.incrementAndGet();
                    if (!teleportResult.get()) {
                        yield false;
                    }
                    Location target = (Location) args[0];
                    location.setWorld(target.getWorld());
                    location.set(target.getX(), target.getY(), target.getZ());
                    location.setYaw(target.getYaw());
                    location.setPitch(target.getPitch());
                    yield true;
                }
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static Player player(Location location) {
        UUID id = UUID.randomUUID();
        return proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getWorld" -> location.getWorld();
            case "getLocation" -> location.clone();
            case "isValid", "isInWorld" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static World world(String value) {
        NamespacedKey key = NamespacedKey.minecraft(value);
        return proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getKey" -> key;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "LifecycleTestProxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return invocation.invoke(proxy, method, args == null ? new Object[0] : args);
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static final class TestContext implements B7CommandContext {
        private final Location origin;
        private boolean constructionAllowed = true;
        private int constructionChecks;
        private boolean setterAllowed = true;
        private int setterWorkCalls;
        private long lastSetterUnits;
        private int buildRange = 1000;
        private Object result;
        private String reason;

        TestContext(Location origin) {
            this.origin = origin;
        }

        @Override public UUID getBoundUuid() { return null; }
        @Override public UUID getConnectionEpoch() { return UUID.randomUUID(); }
        @Override public Location getOrigin() { return origin; }
        @Override public boolean hasConstructionPermission() {
            constructionChecks++;
            return constructionAllowed;
        }
        @Override public boolean isWithinBuildRange(Location target) {
            return RemoteSession.withinBuildRange(origin, target, buildRange);
        }
        @Override public WorkAdmission.Result admitWork(int units) { return WorkAdmission.Result.ACCEPTED; }
        @Override public boolean admitSetterWork(long units) {
            setterWorkCalls++;
            lastSetterUnits = units;
            if (!setterAllowed) {
                reason = "backpressure";
            }
            return setterAllowed;
        }
        @Override public boolean rejectTemporaryBackpressure() { return false; }
        @Override public void respondResult(Object value) {
            result = value;
            reason = null;
        }
        @Override public void respondError(int code, String reason, Map<String, Object> extraData) {
            this.reason = reason;
        }
    }
}
