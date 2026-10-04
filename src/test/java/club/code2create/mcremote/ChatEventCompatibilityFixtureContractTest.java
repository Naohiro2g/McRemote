package club.code2create.mcremote;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/**
 * Exact shared fixture from Naohiro2g/scratch-editor at
 * {@code 62e46fd156a55c57794227d370a72f3558aa43d8},
 * {@code mc-remote/protocol/test/fixtures/chat-event-compat-v23.2.json}.
 * Tests the server's producer paths; client/observer rejection cases belong to their consumers.
 */
class ChatEventCompatibilityFixtureContractTest {
    private static final String FIXTURE = "/fixtures/chat-event-compat-v23.2.json";
    private static final Gson GSON = new Gson();

    @Test
    void exactOwnerBytesAnd33CaseInventory() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream(FIXTURE)) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        assertEquals(32_382, bytes.length);
        assertEquals("670b0a86df1956c0e44c6986a0a2598caab32c7328804f9c703190e62e9dd727",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("mcremote.chat-event-compat.v23.2", root.get("schema").getAsString());
        assertEquals(ProtocolInfo.PROTOCOL, root.get("protocol").getAsString());
        assertEquals(7, root.getAsJsonObject("chat_post").getAsJsonArray("cases").size());
        assertEquals(22, root.getAsJsonObject("event_batches").getAsJsonArray("cases").size());
        assertEquals(4, root.getAsJsonObject("event_batches").getAsJsonArray("stateful_rejections").size());
    }

    @Test
    void productionChatResultMatchesOnlyTheAcceptedAcknowledgedCase() throws Exception {
        JsonObject chat = fixture().getAsJsonObject("chat_post");
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 19);
        request.add("method", chat.get("method"));
        request.add("params", chat.get("params"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            var dispatched = MiscCommandsChatPostTest.dispatch(request.toString());
            JsonObject response = dispatched.response();
            assertEquals(19, response.get("id").getAsInt());
            assertFalse(response.has("error"));
            assertTrue(dispatched.frames().isEmpty());
            for (JsonElement element : chat.getAsJsonArray("cases")) {
                JsonObject item = element.getAsJsonObject();
                assertEquals(item.get("accept").getAsBoolean(),
                        response.get("result").equals(item.get("result")), item.get("id").getAsString());
            }
            bukkit.verify(() -> Bukkit.broadcast(Component.text(chat.getAsJsonArray("params").get(0).getAsString())));
        }
    }

    @Test
    void eventRingPreservesOpaqueTypesAndAdvancesPastUnknownOnlyAndMixedBatches() {
        // These four stateless fixture cases have complete, contiguous producer histories.
        Set<String> applicable = Set.of("B9-E01", "B9-E02", "B9-E06", "B9-E07");
        int checked = 0;
        for (JsonElement element : fixture().getAsJsonObject("event_batches").getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            if (!applicable.contains(item.get("id").getAsString())) continue;
            JsonObject expected = item.getAsJsonObject("result");
            EventRing ring = new EventRing(64, 262_144, 61_312);
            for (JsonElement event : expected.getAsJsonArray("events")) {
                assertTrue(ring.offer(captured(event.getAsJsonObject())));
            }
            assertEquals(expected, GSON.toJsonTree(ring.poll(item.get("after_sequence").getAsLong(), 64)),
                    item.get("id").getAsString());
            checked++;
        }
        assertEquals(applicable.size(), checked);
    }

    @Test
    void opaqueFixtureEventsKeepRealOverflowAndCapacityLossCountersAcrossPolls() {
        JsonObject lossCase = eventCase("B9-E05").getAsJsonObject("result");
        JsonObject opaque = lossCase.getAsJsonArray("events").get(0).getAsJsonObject();
        JsonObject known = eventCase("B9-E01").getAsJsonObject("result").getAsJsonArray("events")
                .get(0).getAsJsonObject();
        EventRing ring = new EventRing(3, 262_144, 61_312);
        for (int sequence = 1; sequence <= 5; sequence++) assertTrue(ring.offer(captured(known)));
        ring.dropForCapacity(); // 6
        ring.dropForCapacity(); // 7
        assertTrue(ring.offer(captured(opaque))); // 8
        ring.dropForCapacity(); // 9
        assertTrue(ring.offer(captured(opaque))); // 10; now four overflow drops, three capacity drops.

        JsonObject first = GSON.toJsonTree(ring.poll(5, 1)).getAsJsonObject();
        JsonObject second = GSON.toJsonTree(ring.poll(first.get("through_sequence").getAsLong(), 64))
                .getAsJsonObject();
        for (JsonObject result : new JsonObject[]{first, second}) {
            for (String counter : new String[]{"overflow_dropped_total", "capacity_dropped_total",
                    "explicitly_discarded_total", "filtered_out"}) {
                assertEquals(lossCase.get(counter), result.get(counter), counter);
            }
            assertEquals(10, result.get("latest_sequence").getAsInt());
            assertEquals(1, result.getAsJsonArray("events").size());
            assertEquals(captured(opaque), captured(result.getAsJsonArray("events").get(0).getAsJsonObject()));
        }
        assertEquals(8, first.get("through_sequence").getAsInt());
        assertEquals(10, second.get("through_sequence").getAsInt());
        assertEquals(8, first.getAsJsonArray("events").get(0).getAsJsonObject().get("sequence").getAsInt());
        assertEquals(10, second.getAsJsonArray("events").get(0).getAsJsonObject().get("sequence").getAsInt());
    }

    private static Map<String, Object> captured(JsonObject event) {
        JsonObject copy = event.deepCopy();
        copy.remove("sequence");
        return GSON.fromJson(copy, new TypeToken<Map<String, Object>>() {}.getType());
    }

    private static JsonObject eventCase(String id) {
        for (JsonElement element : fixture().getAsJsonObject("event_batches").getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            if (id.equals(item.get("id").getAsString())) return item;
        }
        throw new AssertionError("missing fixture case " + id);
    }

    private static JsonObject fixture() {
        try (var input = ChatEventCompatibilityFixtureContractTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(input);
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
