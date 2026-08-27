package club.code2create.mcremote;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture-driven contract test against the exact bytes of scratch-editor's
 * mc-remote/protocol/test/fixtures/events-v23.json (agent/b6-source-refresh@104f194d), the same
 * fixed SHA SignFixtureContractTest and EntityHandleFixtureContractTest use.
 */
class EventsFixtureContractTest {
    private static final String FIXTURE = "/fixtures/events-v23.json";
    private static final Gson GSON = new Gson();

    // B6-P01: B5EventDto.pickaxePoke's shape against the fixture's pickaxe_poke sample event.
    // "sequence" is assigned by the event ring at delivery time, not by the DTO factory, so it is
    // excluded from this comparison rather than faked here.
    @Test
    void b6P01PickaxePokeDtoMatchesFixtureEventShape() {
        JsonObject event = pollResultEvent("pickaxe_poke");

        List<Integer> origin = intList(event.getAsJsonArray("origin"));
        List<Integer> pos = intList(event.getAsJsonArray("pos"));
        @SuppressWarnings("unchecked")
        Map<String, Object> block = GSON.fromJson(event.get("block"), Map.class);

        Map<String, Object> actual = B5EventDto.pickaxePoke(
                event.get("dimension").getAsString(),
                origin,
                pos,
                event.get("face").getAsString(),
                block,
                event.get("hand").getAsString(),
                event.get("item").getAsString());

        JsonObject expected = event.deepCopy();
        expected.remove("sequence");
        assertEquals(expected, GSON.toJsonTree(actual).getAsJsonObject());
    }

    // B6-P02: the retired block_right_click event has no producer in B5EventDto, and the fixture's
    // legacy shape (kept only to document what protocol 22 used to send) has no "item" field —
    // the exact thing pickaxe_poke added when it replaced it.
    @Test
    void b6P02BlockRightClickHasNoProducerAndIsNotRegistered() {
        Set<String> methodNames = Arrays.stream(B5EventDto.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertFalse(methodNames.contains("blockRightClick"),
                "B5EventDto must not regain a block_right_click producer under protocol 23");
        assertTrue(methodNames.containsAll(Set.of("pickaxePoke", "chatPosted", "projectileHit")));

        JsonObject legacy = fixture().getAsJsonObject("legacy_rejected_events")
                .getAsJsonObject("block_right_click");
        assertEquals("block_right_click", legacy.get("type").getAsString());
        assertFalse(legacy.has("item"), "fixture's legacy shape documents the pre-item-field event");
    }

    private static JsonObject pollResultEvent(String type) {
        for (JsonElement element : fixture().getAsJsonObject("poll_result").getAsJsonArray("events")) {
            JsonObject candidate = element.getAsJsonObject();
            if (type.equals(candidate.get("type").getAsString())) {
                return candidate;
            }
        }
        throw new AssertionError("fixture event not found: " + type);
    }

    private static List<Integer> intList(JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsInt).toList();
    }

    private static JsonObject fixture() {
        try (var reader = new InputStreamReader(
                EventsFixtureContractTest.class.getResourceAsStream(FIXTURE), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
