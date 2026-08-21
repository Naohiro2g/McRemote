package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandParserTest {
    private final CommandParser parser = new CommandParser();

    @Test
    void acceptsPositiveScaleIndependentIntegerIdsAndOmittedNotificationId() {
        assertEquals(1, parser.parse(request("1")).getId());
        assertEquals(1, parser.parse(request("1.0")).getId());
        assertEquals(Integer.MAX_VALUE, parser.parse(request("2147483647")).getId());
        assertNull(parser.parse("{\"jsonrpc\":\"2.0\",\"method\":\"world.setBlock\",\"params\":[]}")
                .getId());
    }

    @Test
    void rejectsNonIntegerNullStringAndNonPositiveIds() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(request("1.5")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(request("null")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(request("\"1\"")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(request("0")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(request("-1")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(request("2147483648")));
    }

    private static String request(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"connection.flush\",\"params\":[]}";
    }
}
