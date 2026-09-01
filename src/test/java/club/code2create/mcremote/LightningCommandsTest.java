package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LightningCommandsTest {
    private static final LightningRuntimePolicy POLICY = new LightningRuntimePolicy(20, 20, 2, 20, 8);

    @Test
    void strikesExactFractionalOriginRelativeTargetOnceAfterLockedGateOrder() {
        Fixture fixture = fixture();

        fixture.commands.handleStrikeLightning(JsonParser.parseString("[0.125,-0.25,2.75]"));

        assertEquals(List.of("construction", "range", "work", "chunk", "strike"),
                fixture.order);
        assertEquals(1, fixture.world.strikeCalls.get());
        assertEquals(0, fixture.world.loadCalls.get());
        assertEquals(10.375, fixture.world.strikeTarget.getX());
        assertEquals(63.25, fixture.world.strikeTarget.getY());
        assertEquals(-0.75, fixture.world.strikeTarget.getZ());
        assertEquals(LightningCommands.WORK_UNITS, fixture.context.lastWorkUnits);
        assertEquals(1, fixture.context.resultCalls);
        assertNull(fixture.context.result);
    }

    @Test
    void invalidAndOverflowCoordinatesFailBeforeAuthenticationOrAdmission() {
        Fixture fixture = fixture();
        fixture.context.boundUuid = null;
        JsonArray nonFinite = new JsonArray();
        nonFinite.add(Double.NaN);
        nonFinite.add(0);
        nonFinite.add(0);

        fixture.commands.handleStrikeLightning(nonFinite);
        assertEquals("invalid_params", fixture.context.reason);
        assertEquals(-32602, fixture.context.code);
        assertEquals(List.of(), fixture.order);

        fixture.context.reason = null;
        fixture.context.origin.setX(Double.MAX_VALUE);
        fixture.commands.handleStrikeLightning(JsonParser.parseString("[1.7976931348623157e308,0,0]"));
        assertEquals("invalid_params", fixture.context.reason);
        assertEquals(List.of(), fixture.order);
    }

    @Test
    void requiresBoundIdentityThenSessionConstructionPermissionBeforeRange() {
        Fixture unbound = fixture();
        unbound.context.boundUuid = null;
        unbound.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));
        assertEquals("auth_required", unbound.context.reason);
        assertEquals(List.of(), unbound.order);

        Fixture constructionDenied = fixture();
        constructionDenied.context.constructionAllowed = false;
        constructionDenied.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));
        assertEquals("permission_denied", constructionDenied.context.reason);
        assertEquals(List.of("construction"), constructionDenied.order);

        Fixture rangeDenied = fixture();
        rangeDenied.context.rangeAllowed = false;
        rangeDenied.commands.handleStrikeLightning(JsonParser.parseString("[0,999999,0]"));
        assertEquals("build_denied", rangeDenied.context.reason);
        assertEquals(List.of("construction", "range"), rangeDenied.order);
    }

    @Test
    void rateFailureIsTemporaryAndOccursBeforeWorkOrWorldAccess() {
        Fixture fixture = fixture();
        fixture.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));
        fixture.clearObservation();
        fixture.context.deferTemporary = true;

        assertThrows(CommandDeferredException.class,
                () -> fixture.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]")));
        assertEquals(1, fixture.context.temporaryRejects);
        assertEquals(0, fixture.context.workCalls);
        assertEquals(0, fixture.world.strikeCalls.get());
        assertEquals(List.of("construction", "range"), fixture.order);
    }

    @Test
    void acceptedRateIsNotRefundedWhenWorkBackpressures() {
        Fixture fixture = fixture();
        fixture.context.workResult = WorkAdmission.Result.BACKPRESSURE;

        fixture.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));
        assertEquals(1, fixture.context.workCalls);
        assertEquals(1, fixture.context.temporaryRejects);
        assertEquals(0, fixture.world.strikeCalls.get());

        fixture.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));
        assertEquals(1, fixture.context.workCalls, "rate retry must fail before work");
        assertEquals(2, fixture.context.temporaryRejects);
    }

    @Test
    void configuredWorkLimitFailureIsPermanentAndBeforeChunkAccess() {
        Fixture fixture = fixture();
        fixture.context.workResult = WorkAdmission.Result.WORK_LIMIT_EXCEEDED;

        fixture.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));

        assertEquals("work_limit_exceeded", fixture.context.reason);
        assertEquals(0, fixture.context.temporaryRejects);
        assertEquals(0, fixture.world.loadCalls.get());
        assertEquals(0, fixture.world.strikeCalls.get());
    }

    @Test
    void fixedCostFitsDistributionDefaultButExceedsPolicyBelow256() {
        B5RuntimePolicy distribution = workPolicy(4_096);
        WorkAdmission defaultAdmission = new WorkAdmission(distribution);
        defaultAdmission.beginTick();
        assertEquals(WorkAdmission.Result.ACCEPTED, defaultAdmission.admit(
                UUID.randomUUID(), UUID.randomUUID(), LightningCommands.WORK_UNITS));

        WorkAdmission loweredAdmission = new WorkAdmission(workPolicy(255));
        loweredAdmission.beginTick();
        assertEquals(WorkAdmission.Result.WORK_LIMIT_EXCEEDED, loweredAdmission.admit(
                UUID.randomUUID(), UUID.randomUUID(), LightningCommands.WORK_UNITS));
    }

    @Test
    void unloadedChunkLoadsAtMostOnceAndFailureIsConsumedBackpressure() {
        Fixture fixture = fixture();
        fixture.world.loaded = false;
        fixture.world.loadResult = false;

        fixture.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));

        assertEquals("backpressure", fixture.context.reason);
        assertEquals(0, fixture.context.temporaryRejects,
                "post-admission chunk failure must not defer a notification");
        assertEquals(1, fixture.world.loadCalls.get());
        assertEquals(0, fixture.world.strikeCalls.get());

        fixture.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));
        assertEquals(1, fixture.context.temporaryRejects,
                "the consumed rate token must not be refunded after chunk failure");
        assertEquals(1, fixture.world.loadCalls.get());
    }

    @Test
    void PaperFailureMapsToInternalErrorWithoutSecondStrike() {
        Fixture fixture = fixture();
        fixture.world.throwOnStrike = true;

        fixture.commands.handleStrikeLightning(JsonParser.parseString("[0,0,0]"));

        assertEquals("internal_error", fixture.context.reason);
        assertEquals(1, fixture.world.strikeCalls.get());
        assertEquals(0, fixture.context.resultCalls);
    }

    private static Fixture fixture() {
        List<String> order = new ArrayList<>();
        WorldState worldState = new WorldState(order);
        World world = worldState.proxy();
        UUID playerId = UUID.randomUUID();
        TestContext context = new TestContext(
                playerId, new Location(world, 10.25, 63.5, -3.5), order);
        LightningRateAdmission rate = new LightningRateAdmission(POLICY);
        rate.beginTick();
        LightningCommands commands = new LightningCommands(context, rate, POLICY);
        return new Fixture(commands, context, worldState, order);
    }

    private static B5RuntimePolicy workPolicy(int maxWorkPerRequest) {
        return new B5RuntimePolicy(
                8, 8_000, 8, 8, 8, 1_000,
                maxWorkPerRequest, 4_096, 8_192, 32_768, 16, 16);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "LightningTestProxy";
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

    private static final class WorldState {
        private final List<String> order;
        private final AtomicInteger loadCalls = new AtomicInteger();
        private final AtomicInteger strikeCalls = new AtomicInteger();
        private boolean loaded = true;
        private boolean loadResult = true;
        private boolean throwOnStrike;
        private Location strikeTarget;

        private WorldState(List<String> order) {
            this.order = order;
        }

        private World proxy() {
            return LightningCommandsTest.proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
                case "isChunkLoaded" -> {
                    order.add("chunk");
                    yield loaded;
                }
                case "loadChunk" -> {
                    loadCalls.incrementAndGet();
                    yield loadResult;
                }
                case "strikeLightning" -> {
                    order.add("strike");
                    strikeCalls.incrementAndGet();
                    strikeTarget = ((Location) args[0]).clone();
                    if (throwOnStrike) throw new IllegalStateException("Paper failure");
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static final class TestContext implements B7CommandContext {
        private UUID boundUuid;
        private final UUID connectionEpoch = UUID.randomUUID();
        private final Location origin;
        private final List<String> order;
        private boolean constructionAllowed = true;
        private boolean rangeAllowed = true;
        private boolean deferTemporary;
        private WorkAdmission.Result workResult = WorkAdmission.Result.ACCEPTED;
        private int workCalls;
        private int lastWorkUnits;
        private int temporaryRejects;
        private int resultCalls;
        private Object result;
        private int code;
        private String reason;

        private TestContext(UUID boundUuid, Location origin, List<String> order) {
            this.boundUuid = boundUuid;
            this.origin = origin;
            this.order = order;
        }

        @Override public UUID getBoundUuid() { return boundUuid; }
        @Override public UUID getConnectionEpoch() { return connectionEpoch; }
        @Override public Location getOrigin() { return origin; }

        @Override public boolean hasConstructionPermission() {
            order.add("construction");
            return constructionAllowed;
        }

        @Override public boolean isWithinBuildRange(Location target) {
            order.add("range");
            return rangeAllowed;
        }

        @Override public WorkAdmission.Result admitWork(int units) {
            order.add("work");
            workCalls++;
            lastWorkUnits = units;
            return workResult;
        }

        @Override public boolean admitSetterWork(long units) { return false; }

        @Override public boolean rejectTemporaryBackpressure() {
            temporaryRejects++;
            if (deferTemporary) throw CommandDeferredException.INSTANCE;
            return false;
        }

        @Override public void respondResult(Object value) {
            resultCalls++;
            result = value;
        }

        @Override public void respondError(int code, String reason, Map<String, Object> extraData) {
            this.code = code;
            this.reason = reason;
        }
    }

    private record Fixture(
            LightningCommands commands,
            TestContext context,
            WorldState world,
            List<String> order
    ) {
        private void clearObservation() {
            order.clear();
            context.workCalls = 0;
            context.lastWorkUnits = 0;
            context.temporaryRejects = 0;
            context.resultCalls = 0;
            context.result = null;
            context.reason = null;
            world.loadCalls.set(0);
            world.strikeCalls.set(0);
            world.strikeTarget = null;
        }
    }
}
