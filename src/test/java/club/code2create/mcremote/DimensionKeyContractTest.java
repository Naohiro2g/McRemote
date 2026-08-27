package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionKeyContractTest {
    private static final String FIXTURE = "/fixtures/dimension-key-v23.json";

    @Test
    void fixtureDefinesProtocol23IdentityBoundary() {
        JsonObject fixture = fixture();
        assertEquals("23.0.0", fixture.get("protocol").getAsString());
        assertEquals(DimensionResolver.DEFAULT_DIMENSION,
                fixture.get("default_dimension").getAsString());

        for (JsonObject item : objects(fixture.getAsJsonArray("accepted"))) {
            assertEquals(item.get("canonical").getAsString(),
                    DimensionResolver.parse(item.get("input").getAsString()).toString());
        }
        for (JsonObject item : objects(fixture.getAsJsonArray("not_aliases"))) {
            assertEquals(item.get("canonical").getAsString(),
                    DimensionResolver.parse(item.get("input").getAsString()).toString());
        }
        for (var item : fixture.getAsJsonArray("invalid")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DimensionResolver.parse(item.getAsString()));
        }
        JsonObject wire = fixture.getAsJsonObject("wire");
        assertEquals("build.setDimension", wire.get("method").getAsString());
        assertEquals("build.setWorld", wire.get("removed_method").getAsString());
        assertEquals("unknown_dimension", wire.get("unknown_reason").getAsString());
        assertEquals(EntityHandleRegistry.DIMENSION_CHANGED_REASON,
                wire.get("moved_reason").getAsString());
        assertEquals(List.of("dimension", "origin"),
                wire.getAsJsonArray("context_fields").asList().stream()
                        .map(value -> value.getAsString()).toList());
    }

    @Test
    void resolverUsesNamespacedKeyLookupAndDoesNotFallBack() {
        World overworld = world("minecraft:overworld");
        World custom = world("myworld:world");
        Map<NamespacedKey, World> loaded = new HashMap<>();
        loaded.put(NamespacedKey.fromString("minecraft:overworld"), overworld);
        loaded.put(NamespacedKey.fromString("myworld:world"), custom);
        DimensionResolver resolver = new DimensionResolver(loaded::get);

        assertEquals(overworld, resolver.resolve("overworld").world());
        assertEquals(custom, resolver.resolve("myworld:world").world());
        assertFalse(resolver.resolve("world").isLoaded());
        assertFalse(resolver.resolve("normal").isLoaded());
        assertFalse(resolver.resolve("nether").isLoaded());
        assertFalse(resolver.resolve("end").isLoaded());
    }

    @Test
    void helloBuildDefaultsAndCanonicalizesAtomically() {
        World overworld = world("minecraft:overworld");
        World custom = world("myworld:world");
        DimensionResolver resolver = new DimensionResolver(key -> switch (key.toString()) {
            case "minecraft:overworld" -> overworld;
            case "myworld:world" -> custom;
            default -> null;
        });
        BuildStateCommands commands = new BuildStateCommands(null, resolver);

        Location defaults = commands.resolveHelloBuild(JsonParser.parseString(
                "{\"protocol\":\"23.0.0\"}"));
        assertEquals("minecraft:overworld", DimensionResolver.canonical(defaults.getWorld()));
        assertEquals(List.of(200, 0, 200), coordinates(defaults));

        Location requested = commands.resolveHelloBuild(JsonParser.parseString(
                "{\"protocol\":\"23.0.0\",\"build\":{" +
                        "\"dimension\":\"myworld:world\",\"origin\":[1,-2,3]}}"));
        assertEquals("myworld:world", DimensionResolver.canonical(requested.getWorld()));
        assertEquals(List.of(1, -2, 3), coordinates(requested));
        assertEquals(Map.of("dimension", "myworld:world", "origin", List.of(1, -2, 3)),
                BuildStateCommands.buildContext(requested));
    }

    @Test
    void helloRejectsLegacyWorldUnknownAndMalformedDimension() {
        World overworld = world("minecraft:overworld");
        DimensionResolver resolver = new DimensionResolver(key ->
                "minecraft:overworld".equals(key.toString()) ? overworld : null);
        BuildStateCommands commands = new BuildStateCommands(null, resolver);

        assertThrows(BuildStateCommands.InvalidBuildException.class,
                () -> commands.resolveHelloBuild(JsonParser.parseString(
                        "{\"protocol\":\"23.0.0\",\"build\":{\"world\":\"world\"}}")));
        BuildStateCommands.UnknownDimensionException unknown = assertThrows(
                BuildStateCommands.UnknownDimensionException.class,
                () -> commands.resolveHelloBuild(JsonParser.parseString(
                        "{\"protocol\":\"23.0.0\",\"build\":{\"dimension\":\"world\"}}")));
        assertEquals("minecraft:world", unknown.dimension());
        assertThrows(BuildStateCommands.InvalidBuildException.class,
                () -> commands.resolveHelloBuild(JsonParser.parseString(
                        "{\"protocol\":\"23.0.0\",\"build\":{\"dimension\":\"Overworld\"}}")));
        assertThrows(BuildStateCommands.InvalidBuildException.class,
                () -> commands.resolveHelloBuild(JsonParser.parseString(
                        "{\"protocol\":\"23.0.0\",\"build\":{\"origin\":[1.5,2,3]}}")));
    }

    @Test
    void registryExposesOnlyProtocol23BuildMethods() {
        CommandRegistry registry = new CommandRegistry();
        RemoteCommandRegistrar.registerBuildCommands(
                registry, new BuildStateCommands(null, new DimensionResolver(key -> null)));
        assertNotNull(registry.get("build.setDimension"));
        assertNotNull(registry.get("build.setOrigin"));
        assertNull(registry.get("build.setWorld"));
    }

    @Test
    void buildSettersReturnCanonicalContextAndFailuresDoNotMutate() {
        World overworld = world("minecraft:overworld");
        World nether = world("minecraft:the_nether");
        CapturingBuildSession session = new CapturingBuildSession(
                new Location(overworld, 200, 0, 200));
        BuildStateCommands commands = new BuildStateCommands(session,
                new DimensionResolver(key -> switch (key.toString()) {
                    case "minecraft:overworld" -> overworld;
                    case "minecraft:the_nether" -> nether;
                    default -> null;
                }));

        commands.handleSetDimension(JsonParser.parseString("[\"the_nether\"]"));
        assertEquals(Map.of("dimension", "minecraft:the_nether", "origin", List.of(200, 0, 200)),
                session.result);
        assertEquals("minecraft:the_nether", DimensionResolver.canonical(session.origin.getWorld()));

        commands.handleSetOrigin(JsonParser.parseString("[1,-2,3]"));
        assertEquals(Map.of("dimension", "minecraft:the_nether", "origin", List.of(1, -2, 3)),
                session.result);
        Location beforeFailure = session.origin;

        commands.handleSetDimension(JsonParser.parseString("[\"Overworld\"]"));
        assertEquals("invalid_params", session.reason);
        assertEquals(beforeFailure, session.origin);

        commands.handleSetDimension(JsonParser.parseString("[\"myworld:missing\"]"));
        assertEquals("unknown_dimension", session.reason);
        assertEquals(Map.of("dimension", "myworld:missing"), session.errorData);
        assertEquals(beforeFailure, session.origin);
    }

    private static JsonObject fixture() {
        try (var reader = new InputStreamReader(
                DimensionKeyContractTest.class.getResourceAsStream(FIXTURE), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<JsonObject> objects(JsonArray array) {
        return array.asList().stream().map(value -> value.getAsJsonObject()).toList();
    }

    private static List<Integer> coordinates(Location location) {
        return List.of(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    static World world(String key) {
        NamespacedKey dimensionKey = NamespacedKey.fromString(key);
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getKey" -> dimensionKey;
                    case "toString" -> "WorldTestProxy[" + key + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class CapturingBuildSession implements BuildContextSession {
        private Location origin;
        private Object result;
        private String reason;
        private Map<String, Object> errorData;

        private CapturingBuildSession(Location origin) {
            this.origin = origin;
        }

        @Override
        public Location getOrigin() {
            return origin;
        }

        @Override
        public void setOrigin(Location origin) {
            this.origin = origin;
        }

        @Override
        public void respondResult(Object value) {
            result = value;
            reason = null;
            errorData = null;
        }

        @Override
        public void respondError(int code, String reason, Map<String, Object> extraData) {
            this.reason = reason;
            this.errorData = extraData;
        }
    }
}
