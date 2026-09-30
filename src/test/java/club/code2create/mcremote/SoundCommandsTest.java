package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Protocol 23.2 world.playSound／world.playBlockSound（b8 sound slice）。 */
class SoundCommandsTest {
    // ---- world.playSound ----

    @Test
    void playSoundAtPositionUsesMasterDefaultsAndOneWorkUnit() {
        Harness h = new Harness();
        h.context.result = "unset";

        h.commands.handlePlaySound(params("[1.5, 2, -3, \"minecraft:block.bell.use\"]"));

        assertNull(h.context.reason);
        assertNull(h.context.result);
        assertEquals(1, h.worldPlays.size());
        Played played = h.worldPlays.get(0);
        assertEquals(h.bell, played.sound);
        assertEquals(SoundCategory.MASTER, played.category);
        assertEquals(1.0f, played.volume);
        assertEquals(1.0f, played.pitch);
        assertEquals(101.5, played.location.getX());
        assertEquals(66.0, played.location.getY());
        assertEquals(97.0, played.location.getZ());
        assertEquals(1, h.context.lastWork);
    }

    @Test
    void volumePitchAndNoteOptions() {
        Harness h = new Harness();
        h.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\",{\"volume\":0.25,\"pitch\":1.5}]"));
        assertEquals(0.25f, h.worldPlays.get(0).volume);
        assertEquals(1.5f, h.worldPlays.get(0).pitch);

        for (int[] noteAndHalfOctaves : new int[][]{{0, -12}, {12, 0}, {24, 12}, {14, 2}}) {
            Harness n = new Harness();
            n.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\",{\"note\":" + noteAndHalfOctaves[0] + "}]"));
            assertEquals((float) Math.pow(2.0, noteAndHalfOctaves[1] / 12.0), n.worldPlays.get(0).pitch, 1e-6,
                    "note " + noteAndHalfOctaves[0]);
        }
    }

    @Test
    void optionAndParamViolationsAreInvalidBeforePermission() {
        for (String bad : List.of(
                "[0,0,0,\"minecraft:block.bell.use\",{\"pitch\":1,\"note\":12}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"pitch\":0.49}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"pitch\":2.01}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"volume\":-0.1}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"volume\":1.01}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"note\":25}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"note\":-1}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"note\":1.5}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"source\":\"master\"}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"receiver\":\"all\"}]",
                "[0,0,0,\"minecraft:block.bell.use\",{\"receiver\":5}]",
                "[0,0,0,\"minecraft:block.bell.use\",5]",
                "[0,0,0,5]",
                "[0,0,\"minecraft:block.bell.use\"]",
                "[0,0,0,\"minecraft:block.bell.use\",{},1]")) {
            Harness h = new Harness();
            h.commands.handlePlaySound(params(bad));
            assertEquals("invalid_params", h.context.reason, bad);
            assertEquals(0, h.context.permissionChecks, bad);
            assertEquals(0, h.worldPlays.size(), bad);
        }
    }

    @Test
    void unknownSoundPrecedesReceiverAndPermission() {
        Harness h = new Harness();
        h.context.bound = null;

        h.commands.handlePlaySound(params("[0,0,0,\"minecraft:no.such.sound\",{\"receiver\":\"self\"}]"));

        assertEquals("unknown_sound", h.context.reason);
        assertEquals(0, h.context.permissionChecks);
    }

    @Test
    void selfReceiverPlaysOnlyForTheBoundOnlinePlayer() {
        Harness h = new Harness();
        h.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\",{\"receiver\":\"self\"}]"));
        assertEquals(0, h.worldPlays.size());
        assertEquals(1, h.playerPlays.size());

        Harness unbound = new Harness();
        unbound.context.bound = null;
        unbound.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\",{\"receiver\":\"self\"}]"));
        assertEquals("auth_required", unbound.context.reason);
        assertEquals(0, unbound.context.permissionChecks);

        Harness offline = new Harness();
        offline.online = false;
        offline.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\",{\"receiver\":\"self\"}]"));
        assertEquals("player_offline", offline.context.reason);
    }

    @Test
    void permissionBuildRangeRateAndWorkRunInOrder() {
        Harness denied = new Harness();
        denied.context.permission = false;
        denied.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\"]"));
        assertEquals("permission_denied", denied.context.reason);

        Harness outside = new Harness();
        outside.context.buildRange = 5;
        outside.commands.handlePlaySound(params("[6,0,0,\"minecraft:block.bell.use\"]"));
        assertEquals("build_denied", outside.context.reason);
        assertEquals(0, outside.context.workCalls);

        Harness busy = new Harness(new SoundRateAdmission.Policy(2, 64));
        for (int i = 0; i < 2; i++) {
            busy.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\"]"));
            assertNull(busy.context.reason);
        }
        busy.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\"]"));
        assertEquals("backpressure", busy.context.reason);
        assertEquals(2, busy.context.workCalls, "rate rejection happens before work");
        busy.rate.beginTick();
        busy.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\"]"));
        assertNull(busy.context.reason, "the cap resets each tick");

        Harness overBudget = new Harness();
        overBudget.context.work = WorkAdmission.Result.WORK_LIMIT_EXCEEDED;
        overBudget.commands.handlePlaySound(params("[0,0,0,\"minecraft:block.bell.use\"]"));
        assertEquals("work_limit_exceeded", overBudget.context.reason);
    }

    @Test
    void globalCapIsSharedAcrossConnections() {
        SoundRateAdmission rate = new SoundRateAdmission(new SoundRateAdmission.Policy(16, 2));
        rate.beginTick();
        assertEquals(SoundRateAdmission.Result.ACCEPTED, rate.admit(UUID.randomUUID()));
        assertEquals(SoundRateAdmission.Result.ACCEPTED, rate.admit(UUID.randomUUID()));
        assertEquals(SoundRateAdmission.Result.BACKPRESSURE, rate.admit(UUID.randomUUID()));
    }

    // ---- world.playBlockSound ----

    @Test
    void blockSoundUsesTheBlockSoundGroupAtTheBlockCentre() {
        for (String kind : List.of("place", "hit", "break", "step", "fall")) {
            Harness h = new Harness();
            h.commands.handlePlayBlockSound(params("[1,2,3,\"" + kind + "\"]"));
            assertNull(h.context.reason, kind);
            Played played = h.worldPlays.get(0);
            assertEquals(h.groupSounds.get(kind), played.sound, kind);
            assertEquals(SoundCategory.BLOCKS, played.category, kind);
            assertEquals(0.9f, played.volume, kind + ": SoundGroup default volume");
            assertEquals(0.8f, played.pitch, kind + ": SoundGroup default pitch");
            assertEquals(101.5, played.location.getX(), kind);
            assertEquals(66.5, played.location.getY(), kind);
            assertEquals(103.5, played.location.getZ(), kind);
        }
    }

    @Test
    void blockSoundOptionsOverrideTheGroupDefaults() {
        Harness h = new Harness();
        h.commands.handlePlayBlockSound(params("[0,0,0,\"hit\",{\"note\":24,\"volume\":0.5,\"receiver\":\"self\"}]"));
        assertEquals(0, h.worldPlays.size());
        assertEquals(2.0f, h.playerPlays.get(0).pitch, 1e-6);
        assertEquals(0.5f, h.playerPlays.get(0).volume);
    }

    @Test
    void blockSoundRejectsAirUnknownKindsAndFractionalCoordinates() {
        Harness air = new Harness();
        air.material = Material.CAVE_AIR;
        air.commands.handlePlayBlockSound(params("[0,0,0,\"hit\"]"));
        assertEquals("no_block", air.context.reason);
        assertEquals(0, air.worldPlays.size());

        for (String bad : List.of("[0,0,0,\"land\"]", "[0.5,0,0,\"hit\"]", "[0,0,0,5]")) {
            Harness h = new Harness();
            h.commands.handlePlayBlockSound(params(bad));
            assertEquals("invalid_params", h.context.reason, bad);
        }
    }

    @Test
    void registrarRegistersBothSoundMethodsRequiringOrigin() {
        CommandRegistry registry = new CommandRegistry();
        RemoteCommandRegistrar.registerB8SoundCommands(registry, new Harness().commands);
        assertEquals(true, registry.get("world.playSound").requiresOrigin());
        assertEquals(true, registry.get("world.playBlockSound").requiresOrigin());
    }

    // ---- harness ----

    private static com.google.gson.JsonElement params(String json) {
        return JsonParser.parseString(json);
    }

    private record Played(Location location, String sound, SoundCategory category, float volume, float pitch) {
    }

    private static final class Harness {
        final String bell = "minecraft:block.bell.use";
        final Map<String, String> groupSounds = Map.of(
                "place", "minecraft:block.stone.place", "hit", "minecraft:block.stone.hit",
                "break", "minecraft:block.stone.break", "step", "minecraft:block.stone.step",
                "fall", "minecraft:block.stone.fall");
        final List<Played> worldPlays = new java.util.ArrayList<>();
        final List<Played> playerPlays = new java.util.ArrayList<>();
        final Context context;
        final SoundRateAdmission rate;
        final SoundCommands commands;
        boolean online = true;
        Material material = Material.STONE;

        Harness() {
            this(new SoundRateAdmission.Policy(16, 64));
        }

        Harness(SoundRateAdmission.Policy policy) {
            BlockData data = proxy(BlockData.class, (p, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                default -> defaultValue(method.getReturnType());
            });
            Block block = proxy(Block.class, (p, method, args) -> switch (method.getName()) {
                case "getBlockData" -> data;
                default -> defaultValue(method.getReturnType());
            });
            World world = proxy(World.class, (p, method, args) -> switch (method.getName()) {
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "isChunkLoaded" -> true;
                case "getBlockAt" -> block;
                case "playSound" -> {
                    if (args.length == 5 && args[0] instanceof Location location) {
                        worldPlays.add(new Played(location, (String) args[1], (SoundCategory) args[2],
                                (Float) args[3], (Float) args[4]));
                    }
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
            Player player = proxy(Player.class, (p, method, args) -> switch (method.getName()) {
                case "isOnline" -> online;
                case "playSound" -> {
                    if (args.length == 5 && args[0] instanceof Location location) {
                        playerPlays.add(new Played(location, (String) args[1], (SoundCategory) args[2],
                                (Float) args[3], (Float) args[4]));
                    }
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
            context = new Context(new Location(world, 100, 64, 100));
            rate = new SoundRateAdmission(policy);
            rate.beginTick();
            commands = new SoundCommands(context, rate,
                    key -> key.toString().equals(bell),
                    (blockData, kind) -> new SoundCommands.BlockSound(groupSounds.get(kind), 0.9f, 0.8f),
                    id -> player);
        }
    }

    private static final class Context implements B7CommandContext {
        private final Location origin;
        private final UUID epoch = UUID.randomUUID();
        UUID bound = UUID.randomUUID();
        boolean permission = true;
        int permissionChecks;
        int buildRange = 1000;
        WorkAdmission.Result work = WorkAdmission.Result.ACCEPTED;
        int workCalls;
        int lastWork;
        Object result;
        String reason;

        Context(Location origin) {
            this.origin = origin;
        }

        @Override public UUID getBoundUuid() { return bound; }
        @Override public UUID getConnectionEpoch() { return epoch; }
        @Override public Location getOrigin() { return origin; }
        @Override public boolean hasConstructionPermission() {
            permissionChecks++;
            return permission;
        }
        @Override public boolean isWithinBuildRange(Location target) {
            return RemoteSession.withinBuildRange(origin, target, buildRange);
        }
        @Override public WorkAdmission.Result admitWork(int units) {
            workCalls++;
            lastWork = units;
            return work;
        }
        @Override public boolean admitSetterWork(long units) { return true; }
        @Override public boolean rejectTemporaryBackpressure() {
            reason = "backpressure";
            return false;
        }
        @Override public void respondResult(Object value) {
            result = value;
            reason = null;
        }
        @Override public void respondError(int code, String reason, Map<String, Object> extraData) {
            this.reason = reason;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "SoundTestProxy";
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
