package club.code2create.mcremote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldB5ChunkAdmissionTest {
    private static final String FIXTURE = "/fixtures/b5-chunk-admission-v23.json";

    @Test
    void unloadedChunksAreLoadedAndGeneratedBeforeWorldOperations() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(List.of("world.getHeight", "world.spawnParticle", "world.spawnEntity"),
                fixture.getAsJsonArray("applies_to").asList().stream()
                        .map(element -> element.getAsString()).toList());

        for (var element : fixture.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            int chunkX = item.get("chunk_x").getAsInt();
            int chunkZ = item.get("chunk_z").getAsInt();
            boolean initiallyLoaded = item.get("initially_loaded").getAsBoolean();
            boolean loadResult = item.get("load_result").getAsBoolean();
            AtomicInteger loadCalls = new AtomicInteger();

            World world = world(initiallyLoaded, loadResult, chunkX, chunkZ, loadCalls);
            boolean actual = WorldB5Commands.ensureChunkLoaded(world, chunkX, chunkZ);

            assertEquals(item.get("expected").getAsBoolean(), actual, item.get("name").getAsString());
            assertEquals(item.get("expected_load_calls").getAsInt(), loadCalls.get(),
                    item.get("name").getAsString());
        }
    }

    private static World world(
            boolean initiallyLoaded,
            boolean loadResult,
            int expectedChunkX,
            int expectedChunkZ,
            AtomicInteger loadCalls
    ) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isChunkLoaded" -> {
                        assertEquals(expectedChunkX, args[0]);
                        assertEquals(expectedChunkZ, args[1]);
                        yield initiallyLoaded;
                    }
                    case "loadChunk" -> {
                        assertEquals(expectedChunkX, args[0]);
                        assertEquals(expectedChunkZ, args[1]);
                        assertEquals(true, args[2]);
                        loadCalls.incrementAndGet();
                        yield loadResult;
                    }
                    case "toString" -> "WorldChunkAdmissionTestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static JsonObject fixture() throws IOException {
        try (var stream = WorldB5ChunkAdmissionTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IOException("missing fixture " + FIXTURE);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static Object defaultValue(Class<?> type) {
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
}
