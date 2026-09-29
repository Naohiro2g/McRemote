package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol 23.2 particle Stage 2（wire §5.8.3、DECISIONS 2026-09-23-01／2026-09-30-01）。 */
class ParticleStage2Test {
    private static final String POS = "0,0,0,0,0,0,";

    @Test
    void stringShorthandAndObjectWithoutReceiverStayWorldWideWithoutData() {
        for (String particle : List.of("\"minecraft:flame\"", "{\"particle_id\":\"minecraft:flame\"}",
                "{\"particle_id\":\"minecraft:flame\",\"receiver\":\"world\"}")) {
            Harness h = new Harness();
            h.spawn("[" + POS + particle + ",0,5]");
            assertEquals(5, h.context.result, particle);
            assertSame(Particle.FLAME, h.spawned.particle);
            assertNull(h.spawned.receivers, particle);
            assertNull(h.spawned.data, particle);
            assertEquals(5, h.context.lastWork);
        }
    }

    @Test
    void dustDataBecomesDustOptionsAndBoundarySizesAreAccepted() {
        for (String size : List.of("0.01", "1.5", "4.0")) {
            Harness h = new Harness();
            h.spawn("[" + POS + "{\"particle_id\":\"minecraft:dust\",\"data\":{\"color\":[255,0,16],\"size\":"
                    + size + "}},0,3]");
            Particle.DustOptions options = (Particle.DustOptions) h.spawned.data;
            assertEquals(Color.fromRGB(255, 0, 16), options.getColor(), size);
            assertEquals(Float.parseFloat(size), options.getSize(), size);
        }
    }

    @Test
    void blockDataUsesTheExistingBlockSpecCodec() {
        Harness h = new Harness();
        h.spawn("[" + POS + "{\"particle_id\":\"minecraft:block\",\"data\":"
                + "{\"block_id\":\"minecraft:stone\",\"state\":{}}},0,2]");
        assertSame(h.stoneData, h.spawned.data);

        Harness unknown = new Harness();
        unknown.spawn("[" + POS + "{\"particle_id\":\"minecraft:block\",\"data\":"
                + "{\"block_id\":\"minecraft:not_a_block\",\"state\":{}}},0,2]");
        assertEquals("unknown_block", unknown.context.reason);
        assertNull(unknown.spawned.particle);
    }

    @Test
    void dustSchemaViolationsAreInvalidParams() {
        for (String data : List.of(
                "{\"color\":[256,0,0],\"size\":1}", "{\"color\":[-1,0,0],\"size\":1}",
                "{\"color\":[1.5,0,0],\"size\":1}", "{\"color\":[1,2],\"size\":1}",
                "{\"color\":[1,2,3],\"size\":0.009}", "{\"color\":[1,2,3],\"size\":4.01}",
                "{\"color\":[1,2,3]}", "{\"color\":[1,2,3],\"size\":1,\"alpha\":1}",
                "{\"color\":\"red\",\"size\":1}", "5")) {
            Harness h = new Harness();
            h.spawn("[" + POS + "{\"particle_id\":\"minecraft:dust\",\"data\":" + data + "},0,1]");
            assertEquals("invalid_params", h.context.reason, data);
            assertEquals(0, h.context.permissionChecks, data);
        }
    }

    @Test
    void specShapeViolationsAreInvalidParams() {
        for (String particle : List.of(
                "{\"particle_id\":\"minecraft:flame\",\"data\":null}",
                "{\"particle_id\":\"minecraft:flame\",\"color\":[1,2,3]}",
                "{\"receiver\":\"world\"}",
                "{\"particle_id\":5}",
                "{\"particle_id\":\"minecraft:flame\",\"receiver\":\"all\"}",
                "{\"particle_id\":\"minecraft:flame\",\"receiver\":5}",
                "[\"minecraft:flame\"]")) {
            Harness h = new Harness();
            h.spawn("[" + POS + particle + ",0,1]");
            assertEquals("invalid_params", h.context.reason, particle);
        }
    }

    @Test
    void missingAndUnsupportedDataReasons() {
        Harness shorthand = new Harness();
        shorthand.spawn("[" + POS + "\"minecraft:dust\",0,1]");
        assertEquals("particle_data_required", shorthand.context.reason);

        Harness objectWithoutData = new Harness();
        objectWithoutData.spawn("[" + POS + "{\"particle_id\":\"minecraft:dust\"},0,1]");
        assertEquals("particle_data_required", objectWithoutData.context.reason);

        for (String particle : List.of("minecraft:flame", "minecraft:item", "minecraft:falling_dust")) {
            Harness h = new Harness();
            h.spawn("[" + POS + "{\"particle_id\":\"" + particle + "\",\"data\":{\"color\":[1,2,3],\"size\":1}},0,1]");
            assertEquals("particle_data_unsupported", h.context.reason, particle);
        }

        Harness nonObject = new Harness();
        nonObject.spawn("[" + POS + "{\"particle_id\":\"minecraft:flame\",\"data\":5},0,1]");
        assertEquals("invalid_params", nonObject.context.reason);
    }

    @Test
    void selfReceiverTargetsOnlyTheBoundOnlinePlayerAndKeepsForce() {
        Harness h = new Harness();
        h.spawn("[" + POS + "{\"particle_id\":\"minecraft:flame\",\"receiver\":\"self\"},0,4,false]");

        assertEquals(List.of(h.player), h.spawned.receivers);
        assertEquals(false, h.spawned.force);
        assertEquals(4, h.context.result);
    }

    @Test
    void selfReceiverRequiresAuthenticationAndAnOnlinePlayerBeforePermission() {
        Harness unbound = new Harness();
        unbound.bound = null;
        unbound.spawn("[" + POS + "{\"particle_id\":\"minecraft:flame\",\"receiver\":\"self\"},0,1]");
        assertEquals("auth_required", unbound.context.reason);
        assertEquals(0, unbound.context.permissionChecks);

        Harness offline = new Harness();
        offline.online = false;
        offline.spawn("[" + POS + "{\"particle_id\":\"minecraft:flame\",\"receiver\":\"self\"},0,1]");
        assertEquals("player_offline", offline.context.reason);
    }

    @Test
    void combinedErrorsFollowTheContractPriority() {
        Harness unknownAndUnauthenticated = new Harness();
        unknownAndUnauthenticated.bound = null;
        unknownAndUnauthenticated.spawn(
                "[" + POS + "{\"particle_id\":\"minecraft:nope\",\"receiver\":\"self\"},0,1]");
        assertEquals("unknown_particle", unknownAndUnauthenticated.context.reason);

        Harness badDataAndUnauthenticated = new Harness();
        badDataAndUnauthenticated.bound = null;
        badDataAndUnauthenticated.spawn("[" + POS + "{\"particle_id\":\"minecraft:dust\",\"receiver\":\"self\","
                + "\"data\":{\"color\":[1,2,3],\"size\":9}},0,1]");
        assertEquals("invalid_params", badDataAndUnauthenticated.context.reason);

        Harness goodDataAndUnauthenticated = new Harness();
        goodDataAndUnauthenticated.bound = null;
        goodDataAndUnauthenticated.spawn("[" + POS + "{\"particle_id\":\"minecraft:dust\",\"receiver\":\"self\","
                + "\"data\":{\"color\":[1,2,3],\"size\":1}},0,1]");
        assertEquals("auth_required", goodDataAndUnauthenticated.context.reason);
    }

    @Test
    void countPolicyPrecedesParticleShapeAndPermissionPrecedesWork() {
        Harness overCount = new Harness();
        overCount.spawn("[" + POS + "{\"particle_id\":\"minecraft:nope\"},0,1001]");
        assertEquals("work_limit_exceeded", overCount.context.reason);

        Harness denied = new Harness();
        denied.context.permission = false;
        denied.spawn("[" + POS + "{\"particle_id\":\"minecraft:flame\"},0,1]");
        assertEquals("permission_denied", denied.context.reason);
        assertEquals(0, denied.context.workCalls);
    }

    // ---- harness ----

    private static final class Harness {
        final Spawned spawned = new Spawned();
        final Context context;
        final BlockData stoneData = proxy(BlockData.class, (p, m, a) -> defaultValue(m.getReturnType()));
        UUID bound = UUID.randomUUID();
        boolean online = true;
        final Player player;

        Harness() {
            World world = proxy(World.class, (p, method, args) -> switch (method.getName()) {
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "isChunkLoaded" -> true;
                case "spawnParticle" -> {
                    if (args.length == 13) {
                        spawned.particle = (Particle) args[0];
                        spawned.receivers = args[1];
                        spawned.data = args[11];
                        spawned.force = (Boolean) args[12];
                    }
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
            context = new Context(new Location(world, 0, 64, 0));
            player = proxy(Player.class, (p, method, args) -> switch (method.getName()) {
                case "isOnline" -> online;
                default -> defaultValue(method.getReturnType());
            });
        }

        void spawn(String params) {
            BlockCodec codec = new BlockCodec(
                    id -> "minecraft:stone".equals(id) ? Map.of() : null,
                    serialized -> stoneData);
            RuntimePolicy policy = RuntimePolicy.from(new YamlConfiguration());
            new WorldB5Commands(context, new EntityHandleRegistry(4), policy, ParticleStage2Test::particle,
                    codec, () -> bound, id -> player).handleSpawnParticle(JsonParser.parseString(params));
        }
    }

    /** Registry lookup needs a server; tests resolve the particles they use by canonical key. */
    private static Particle particle(String id) {
        for (Particle candidate : List.of(
                Particle.FLAME, Particle.DUST, Particle.BLOCK, Particle.ITEM, Particle.FALLING_DUST)) {
            if (candidate.getKey().toString().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    private static final class Spawned {
        Particle particle;
        Object receivers;
        Object data;
        Boolean force;
    }

    private static final class Context implements WorldCommandContext {
        private final Location origin;
        boolean permission = true;
        int permissionChecks;
        int workCalls;
        int lastWork;
        Object result;
        String reason;

        Context(Location origin) {
            this.origin = origin;
        }

        @Override public Location getOrigin() { return origin; }
        @Override public boolean hasConstructionPermission() {
            permissionChecks++;
            return permission;
        }
        @Override public boolean isWithinBuildRange(Location target) { return true; }
        @Override public WorkAdmission.Result admitWork(int units) {
            workCalls++;
            lastWork = units;
            return WorkAdmission.Result.ACCEPTED;
        }
        @Override public void respondResult(Object value) { result = value; }
        @Override public void respondError(int code, String reason, Map<String, Object> data) {
            this.reason = reason;
            assertTrue(code == -32602 || code == -32000);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "ParticleTestProxy";
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
}
