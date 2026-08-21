package club.code2create.mcremote;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRingTest {
    @Test
    void pollIsNonDestructiveAndCursorAdvancesOnlyThroughReturnedLimit() {
        EventRing ring = ring(8, 8_000);
        ring.offer(event("one"));
        ring.offer(event("two"));

        Map<String, Object> first = ring.poll(0, 1);
        Map<String, Object> repeated = ring.poll(0, 1);

        assertEquals(first, repeated);
        assertEquals(1L, first.get("through_sequence"));
        assertEquals(2L, first.get("latest_sequence"));
        assertEquals(1, events(first).size());
    }

    @Test
    void staleCursorReturnsRetainedEventsAndReportsOverflow() {
        EventRing ring = ring(2, 8_000);
        ring.offer(event("one"));
        ring.offer(event("two"));
        ring.offer(event("three"));

        Map<String, Object> result = ring.poll(0, 8);
        assertEquals(List.of(2L, 3L), events(result).stream()
                .map(item -> ((Number) item.get("sequence")).longValue()).toList());
        assertEquals(1L, result.get("overflow_dropped_total"));
        assertEquals(3L, result.get("through_sequence"));
    }

    @Test
    void capacityDropConsumesSequenceAndIsVisible() {
        EventRing ring = ring(8, 300);
        assertFalse(ring.offer(event("x".repeat(1_000))));

        Map<String, Object> result = ring.poll(0, 8);
        assertEquals(1L, result.get("latest_sequence"));
        assertEquals(1L, result.get("through_sequence"));
        assertEquals(1L, result.get("capacity_dropped_total"));
        assertTrue(events(result).isEmpty());
    }

    @Test
    void latestAndFutureCursorHaveDistinctSemantics() {
        EventRing ring = ring(8, 8_000);
        ring.offer(event("one"));
        assertTrue(events(ring.poll(1, 8)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ring.poll(2, 8));
    }

    @Test
    void clearCountsOnlyExplicitlyDiscardedRetainedEvents() {
        EventRing ring = ring(8, 8_000);
        ring.offer(event("one"));
        ring.offer(event("two"));
        ring.clear();
        Map<String, Object> result = ring.poll(0, 8);
        assertEquals(2L, result.get("explicitly_discarded_total"));
        assertEquals(2L, result.get("through_sequence"));
    }

    @Test
    void resultStaysBelowCompactResponseAdmission() {
        EventRing ring = new EventRing(100, 200_000, 4_000);
        for (int i = 0; i < 40; i++) {
            ring.offer(event("x".repeat(300)));
        }
        Map<String, Object> result = ring.poll(0, 40);
        int bytes = new Gson().toJson(result).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 4_000, "actual bytes=" + bytes);
        assertTrue(((Number) result.get("through_sequence")).longValue()
                < ((Number) result.get("latest_sequence")).longValue());
    }

    @Test
    void completeJsonRpcResponseStaysWithin61440BytesAndRemainsParseable() {
        int resultBudget = RemoteSession.resultPayloadBudget(
                Integer.MAX_VALUE, RemoteSession.MAX_EVENT_POLL_RESPONSE_BYTES);
        EventRing ring = new EventRing(256, 262_144, resultBudget);
        String escaped = ("\\\"\n\u0000漢字").repeat(610);
        for (int i = 0; i < 40; i++) {
            assertTrue(ring.offer(event(escaped + i)));
        }

        Map<String, Object> result = ring.poll(0, 64);
        String response = RemoteSession.encodeResultResponse(Integer.MAX_VALUE, result);
        int responseBytes = response.getBytes(StandardCharsets.UTF_8).length;

        assertTrue(responseBytes <= RemoteSession.MAX_EVENT_POLL_RESPONSE_BYTES,
                "actual bytes=" + responseBytes);
        assertTrue(responseBytes > 55_000, "fixture did not exercise the boundary: " + responseBytes);
        assertTrue(events(result).size() < 40, "byte limit must win before the count limit");
        assertTrue(((Number) result.get("through_sequence")).longValue()
                < ((Number) result.get("latest_sequence")).longValue());

        JsonObject envelope = JsonParser.parseString(response).getAsJsonObject();
        assertEquals("2.0", envelope.get("jsonrpc").getAsString());
        assertEquals(Integer.MAX_VALUE, envelope.get("id").getAsInt());
        assertTrue(envelope.has("result"));
        assertEquals(7, envelope.getAsJsonObject("result").size());
    }

    private static EventRing ring(int capacity, int bytes) {
        return new EventRing(capacity, bytes, 61_312);
    }

    private static Map<String, Object> event(String message) {
        return Map.of("type", "chat_posted", "message", message);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("events");
    }
}
