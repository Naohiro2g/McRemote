package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireParamsTest {
    @Test
    void integerAcceptsMathematicallyIntegralJsonNumbers() {
        JsonArray args = JsonParser.parseString("[1,1.0,1e0]").getAsJsonArray();
        assertEquals(1, WireParams.integer(args, 0));
        assertEquals(1, WireParams.integer(args, 1));
        assertEquals(1, WireParams.integer(args, 2));
    }

    @Test
    void integerRejectsFractionStringAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> WireParams.integer(array("[1.5]"), 0));
        assertThrows(IllegalArgumentException.class, () -> WireParams.integer(array("[\"1\"]"), 0));
        assertThrows(IllegalArgumentException.class, () -> WireParams.integer(array("[2147483648]"), 0));
    }

    @Test
    void finiteDoubleRequiresJsonNumber() {
        assertEquals(1.25, WireParams.finiteDouble(array("[1.25]"), 0));
        assertThrows(IllegalArgumentException.class, () -> WireParams.finiteDouble(array("[\"1.25\"]"), 0));
        assertThrows(IllegalArgumentException.class, () -> WireParams.finiteDouble(array("[1e400]"), 0));
    }

    @Test
    void exactEmptyParamsAcceptsOnlyAnEmptyArray() {
        assertEquals(0, WireParams.positional(array("[]"), 0).size());
        assertThrows(IllegalArgumentException.class,
                () -> WireParams.positional(JsonParser.parseString("{}"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> WireParams.positional(array("[null]"), 0));
    }

    private static JsonArray array(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }
}
