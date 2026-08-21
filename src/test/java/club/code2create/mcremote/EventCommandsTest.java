package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventCommandsTest {
    private static final int DEFAULT_LIMIT = 64;
    private static final int SERVER_LIMIT = 64;

    @Test
    void omittedOptionsUseServerDefault() {
        EventCommands.PollRequest request = parse("[7]");
        assertEquals(7L, request.afterSequence());
        assertEquals(DEFAULT_LIMIT, request.maxEvents());
    }

    @Test
    void maxEventsIsAClientHintCappedByServerLimit() {
        assertEquals(3, parse("[0,{\"max_events\":3}]").maxEvents());
        assertEquals(SERVER_LIMIT, parse("[0,{\"max_events\":1000}]").maxEvents());
        assertEquals(SERVER_LIMIT,
                parse("[0,{\"max_events\":9223372036854775808}]").maxEvents());
        assertEquals(SERVER_LIMIT,
                parse("[0,{\"max_events\":1e100000000}]").maxEvents());
    }

    @Test
    void rejectsLegacyFlatLimitAndUnknownOrMissingOptions() {
        assertInvalid("[0,8]");
        assertInvalid("[0,{}]");
        assertInvalid("[0,{\"limit\":8}]");
        assertInvalid("[0,{\"max_events\":8,\"filter\":{}}]");
    }

    @Test
    void rejectsNonPositiveAndNonIntegerMaxEvents() {
        assertInvalid("[0,{\"max_events\":0}]");
        assertInvalid("[0,{\"max_events\":-1}]");
        assertInvalid("[0,{\"max_events\":1.5}]");
        assertInvalid("[0,{\"max_events\":\"1\"}]");
        assertInvalid("[0,{\"max_events\":true}]");
    }

    private static EventCommands.PollRequest parse(String json) {
        return EventCommands.parseRequest(
                JsonParser.parseString(json), DEFAULT_LIMIT, SERVER_LIMIT);
    }

    private static void assertInvalid(String json) {
        assertThrows(IllegalArgumentException.class, () -> parse(json));
    }
}
