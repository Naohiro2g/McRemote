package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DirectionCommandsTest {
    @Test
    void playerGetReturnsUnitDirectionWithoutWorkAdmission() {
        Fixture fixture = fixture(-90.0f, 0.0f);

        fixture.commands.handlePlayerGet(JsonParser.parseString("[]"));

        assertEquals(DirectionValue.normalize(1, 0, 0).wire(), fixture.context.result);
        assertEquals(0, fixture.context.setterWorkCalls);
    }

    @Test
    void playerSetUsesOneUnitAndChangesOnlyRotation() {
        Fixture fixture = fixture(0.0f, 0.0f);
        Location before = fixture.location.clone();

        fixture.commands.handlePlayerSet(JsonParser.parseString("[10,0,0]"));

        assertEquals(1, fixture.context.setterWorkCalls);
        assertEquals(1, fixture.context.lastSetterUnits);
        assertEquals(1, fixture.rotationCalls.get());
        assertEquals(DirectionValue.normalize(1, 0, 0).wire(), fixture.context.result);
        assertEquals(before.getX(), fixture.location.getX());
        assertEquals(before.getY(), fixture.location.getY());
        assertEquals(before.getZ(), fixture.location.getZ());
        assertSame(before.getWorld(), fixture.location.getWorld());
    }

    @Test
    void zeroAndInvalidDirectionsFailBeforePermissionHandleOrWork() {
        Fixture fixture = fixture(0.0f, 0.0f);
        fixture.context.boundUuid = null;

        fixture.commands.handlePlayerSet(JsonParser.parseString("[-0.0,0,0]"));
        assertEquals("zero_direction", fixture.context.reason);
        assertEquals(0, fixture.permissions.onlineChecks.get());
        assertEquals(0, fixture.context.setterWorkCalls);

        fixture.context.reason = null;
        JsonArray invalid = new JsonArray();
        invalid.add(Double.NaN);
        invalid.add(0);
        invalid.add(1);
        fixture.commands.handlePlayerSet(invalid);
        assertEquals("invalid_params", fixture.context.reason);
        assertEquals(0, fixture.permissions.onlineChecks.get());
        assertEquals(0, fixture.context.setterWorkCalls);

        fixture.context.reason = null;
        fixture.commands.handlePlayerSet(JsonParser.parseString("[1,2]"));
        assertEquals("invalid_params", fixture.context.reason);
        fixture.context.reason = null;
        fixture.commands.handlePlayerSet(JsonParser.parseString("[\"1\",2,3]"));
        assertEquals("invalid_params", fixture.context.reason);
    }

    @Test
    void playerPermissionAndOnlineStatePrecedeSetterAdmission() {
        Fixture fixture = fixture(0.0f, 0.0f);
        fixture.permissions.onlineAllowed = false;

        fixture.commands.handlePlayerSet(JsonParser.parseString("[0,0,1]"));

        assertEquals("permission_denied", fixture.context.reason);
        assertEquals(1, fixture.permissions.onlineChecks.get());
        assertEquals(0, fixture.context.setterWorkCalls);
        assertEquals(0, fixture.rotationCalls.get());
    }

    @Test
    void offlinePlayerFailsBeforePermissionAndWork() {
        Fixture fixture = fixture(0.0f, 0.0f);
        fixture.online.set(false);

        fixture.commands.handlePlayerGet(JsonParser.parseString("[]"));

        assertEquals("player_offline", fixture.context.reason);
        assertEquals(0, fixture.permissions.onlineChecks.get());
        assertEquals(0, fixture.context.setterWorkCalls);
    }

    @Test
    void PaperOrPostReadFailureIsInternalAndNeverRetried() {
        Fixture paperFailure = fixture(0.0f, 0.0f);
        paperFailure.failRotation.set(true);
        paperFailure.commands.handlePlayerSet(JsonParser.parseString("[0,0,1]"));
        assertEquals("internal_error", paperFailure.context.reason);
        assertEquals(1, paperFailure.rotationCalls.get());

        Fixture readFailure = fixture(0.0f, 0.0f);
        readFailure.failReadAfterRotation.set(true);
        readFailure.commands.handlePlayerSet(JsonParser.parseString("[0,0,1]"));
        assertEquals("internal_error", readFailure.context.reason);
        assertEquals(1, readFailure.rotationCalls.get());
    }

    @Test
    void entityPermissionPrecedesHandleResolutionAndSetAdmission() {
        Fixture fixture = fixture(0.0f, 0.0f);
        fixture.context.constructionAllowed = false;
        String handle = fixture.handles.issue(fixture.entity);

        fixture.commands.handleEntitySet(JsonParser.parseString(
                "[\"" + handle + "\",0,0,1]"));

        assertEquals("permission_denied", fixture.context.reason);
        assertEquals(0, fixture.entityLookups.get());
        assertEquals(0, fixture.context.setterWorkCalls);
        assertEquals(0, fixture.rotationCalls.get());
    }

    @Test
    void entitySetResolvesHandleThenUsesOneWorkUnitAndPostReads() {
        Fixture fixture = fixture(0.0f, 0.0f);
        String handle = fixture.handles.issue(fixture.entity);
        Location before = fixture.location.clone();

        fixture.commands.handleEntitySet(JsonParser.parseString(
                "[\"" + handle + "\",0,1,0]"));

        assertEquals(1, fixture.entityLookups.get());
        assertEquals(1, fixture.context.setterWorkCalls);
        assertEquals(1, fixture.context.lastSetterUnits);
        assertEquals(1, fixture.rotationCalls.get());
        assertEquals(DirectionValue.normalize(0, 1, 0).wire(), fixture.context.result);
        assertEquals(before.getX(), fixture.location.getX());
        assertEquals(before.getY(), fixture.location.getY());
        assertEquals(before.getZ(), fixture.location.getZ());
        assertSame(before.getWorld(), fixture.location.getWorld());
    }

    @Test
    void unknownAndMalformedEntityHandlesStayOpaque() {
        Fixture fixture = fixture(0.0f, 0.0f);

        fixture.commands.handleEntityGet(JsonParser.parseString("[\"\"]"));
        assertEquals("entity_not_found", fixture.context.reason);
        assertEquals(-32000, fixture.context.code);

        fixture.context.reason = null;
        fixture.commands.handleEntityGet(JsonParser.parseString("[1]"));
        assertEquals("invalid_params", fixture.context.reason);
        assertEquals(-32602, fixture.context.code);
    }

    @Test
    void unavailableHandleReportsOnceThenBecomesNotFound() {
        Fixture fixture = fixture(0.0f, 0.0f);
        String handle = fixture.handles.issue(fixture.entity);
        fixture.lookupEntity.set(null);

        fixture.commands.handleEntityGet(JsonParser.parseString("[\"" + handle + "\"]"));
        assertEquals("entity_unavailable", fixture.context.reason);

        fixture.context.reason = null;
        fixture.commands.handleEntityGet(JsonParser.parseString("[\"" + handle + "\"]"));
        assertEquals("entity_not_found", fixture.context.reason);
    }

    @Test
    void dimensionChangedHandleReportsOnceThenBecomesNotFound() {
        Fixture fixture = fixture(0.0f, 0.0f);
        String handle = fixture.handles.issue(fixture.entity);
        Location moved = new Location(world("the_nether"), 10.25, 64.5, -3.75);
        fixture.lookupEntity.set(entity(
                fixture.entity.getUniqueId(), moved, new AtomicInteger(),
                new AtomicBoolean(), new AtomicBoolean()));

        fixture.commands.handleEntityGet(JsonParser.parseString("[\"" + handle + "\"]"));
        assertEquals("entity_dimension_changed", fixture.context.reason);

        fixture.context.reason = null;
        fixture.commands.handleEntityGet(JsonParser.parseString("[\"" + handle + "\"]"));
        assertEquals("entity_not_found", fixture.context.reason);
    }

    private static Fixture fixture(float yaw, float pitch) {
        UUID playerId = UUID.randomUUID();
        World world = world();
        Location location = new Location(world, 10.25, 64.5, -3.75, yaw, pitch);
        AtomicInteger rotations = new AtomicInteger();
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicBoolean failRotation = new AtomicBoolean();
        AtomicBoolean failReadAfterRotation = new AtomicBoolean();
        Player player = player(
                playerId, location, rotations, online, failRotation, failReadAfterRotation);
        Entity entity = entity(
                UUID.randomUUID(), location, rotations, failRotation, failReadAfterRotation);
        AtomicReference<Entity> lookupEntity = new AtomicReference<>(entity);
        AtomicInteger lookups = new AtomicInteger();
        EntityHandleRegistry handles = new EntityHandleRegistry(
                4, new SecureRandom(), ignored -> {
                    lookups.incrementAndGet();
                    return lookupEntity.get();
                });
        TestContext context = new TestContext(playerId, location);
        TestPermissions permissions = new TestPermissions();
        OfflinePlayer offline = offlinePlayer(playerId);
        DirectionCommands commands = new DirectionCommands(
                context, handles, permissions, ignored -> player, ignored -> offline);
        return new Fixture(
                commands, context, permissions, handles, location, rotations, lookups, entity,
                online, failRotation, failReadAfterRotation, lookupEntity);
    }

    private static World world() {
        return world("overworld");
    }

    private static World world(String value) {
        NamespacedKey key = NamespacedKey.minecraft(value);
        return proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getKey" -> key;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Player player(
            UUID uuid,
            Location location,
            AtomicInteger rotations,
            AtomicBoolean online,
            AtomicBoolean failRotation,
            AtomicBoolean failReadAfterRotation
    ) {
        return proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getWorld" -> location.getWorld();
            case "getLocation" -> {
                if (failReadAfterRotation.get() && rotations.get() > 0) {
                    throw new IllegalStateException("post-read failure");
                }
                yield location.clone();
            }
            case "isOnline" -> online.get();
            case "isValid", "isInWorld" -> true;
            case "isDead" -> false;
            case "setRotation" -> {
                location.setYaw((float) args[0]);
                location.setPitch((float) args[1]);
                rotations.incrementAndGet();
                if (failRotation.get()) throw new IllegalStateException("setRotation failure");
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Entity entity(
            UUID uuid,
            Location location,
            AtomicInteger rotations,
            AtomicBoolean failRotation,
            AtomicBoolean failReadAfterRotation
    ) {
        return proxy(Entity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getWorld" -> location.getWorld();
            case "getLocation" -> {
                if (failReadAfterRotation.get() && rotations.get() > 0) {
                    throw new IllegalStateException("post-read failure");
                }
                yield location.clone();
            }
            case "isValid", "isInWorld" -> true;
            case "isDead" -> false;
            case "setRotation" -> {
                location.setYaw((float) args[0]);
                location.setPitch((float) args[1]);
                rotations.incrementAndGet();
                if (failRotation.get()) throw new IllegalStateException("setRotation failure");
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static OfflinePlayer offlinePlayer(UUID uuid) {
        return proxy(OfflinePlayer.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> "DirectionTester";
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "DirectionTestProxy";
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
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static final class TestContext implements B7CommandContext {
        private UUID boundUuid;
        private final UUID connectionEpoch = UUID.randomUUID();
        private final Location origin;
        private boolean constructionAllowed = true;
        private boolean setterAllowed = true;
        private int setterWorkCalls;
        private long lastSetterUnits;
        private Object result;
        private int code;
        private String reason;

        private TestContext(UUID boundUuid, Location origin) {
            this.boundUuid = boundUuid;
            this.origin = origin;
        }

        @Override public UUID getBoundUuid() { return boundUuid; }
        @Override public UUID getConnectionEpoch() { return connectionEpoch; }
        @Override public Location getOrigin() { return origin; }
        @Override public boolean hasConstructionPermission() { return constructionAllowed; }
        @Override public boolean isWithinBuildRange(Location target) { return true; }
        @Override public WorkAdmission.Result admitWork(int units) { return WorkAdmission.Result.ACCEPTED; }

        @Override
        public boolean admitSetterWork(long units) {
            setterWorkCalls++;
            lastSetterUnits = units;
            return setterAllowed;
        }

        @Override public boolean rejectTemporaryBackpressure() { return false; }
        @Override public void respondResult(Object value) { result = value; }

        @Override
        public void respondError(int code, String reason, Map<String, Object> extraData) {
            this.code = code;
            this.reason = reason;
        }
    }

    private static final class TestPermissions implements IPermissionManager {
        private final AtomicInteger onlineChecks = new AtomicInteger();
        private boolean onlineAllowed = true;

        @Override public boolean canConstructOnline(OfflinePlayer player) {
            onlineChecks.incrementAndGet();
            return onlineAllowed;
        }
        @Override public boolean canConstructOffline(OfflinePlayer player) { return true; }
        @Override public int getPlayerRange(OfflinePlayer player) { return 100; }
    }

    private record Fixture(
            DirectionCommands commands,
            TestContext context,
            TestPermissions permissions,
            EntityHandleRegistry handles,
            Location location,
            AtomicInteger rotationCalls,
            AtomicInteger entityLookups,
            Entity entity,
            AtomicBoolean online,
            AtomicBoolean failRotation,
            AtomicBoolean failReadAfterRotation,
            AtomicReference<Entity> lookupEntity
    ) {
    }
}
