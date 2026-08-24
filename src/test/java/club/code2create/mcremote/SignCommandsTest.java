package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignCommandsTest {

    // ---- SignSpec parsing (pure, no Bukkit types) ----

    @Test
    void acceptsFrontOnlyAndLeavesBackNull() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"front": ["a", "b", "c", "d"]}"""));
        assertArrayEquals(new String[]{"a", "b", "c", "d"}, spec.front);
        assertNull(spec.back);
    }

    @Test
    void acceptsBackOnlyAndLeavesFrontNull() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"back": ["1", "2", "3", "4"]}"""));
        assertNull(spec.front);
        assertArrayEquals(new String[]{"1", "2", "3", "4"}, spec.back);
    }

    @Test
    void acceptsBothFacesIndependently() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"front": ["a", "", "", ""], "back": ["b", "", "", ""]}"""));
        assertEquals("a", spec.front[0]);
        assertEquals("b", spec.back[0]);
    }

    @Test
    void rejectsSpecWithNeitherFaceGiven() {
        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("{}")));
        assertEquals("invalid_params", e.reason);
        assertEquals("params[3]", e.data.get("path"));
    }

    @Test
    void rejectsUnknownFaceField() {
        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("""
                        {"front": ["a", "b", "c", "d"], "side": ["x"]}""")));
        assertEquals("invalid_params", e.reason);
        assertEquals("params[3].side", e.data.get("path"));
    }

    @Test
    void rejectsFaceWithFewerThanFourLines() {
        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("""
                        {"front": ["a", "b", "c"]}""")));
        assertEquals("params[3].front", e.data.get("path"));
    }

    @Test
    void rejectsFaceWithMoreThanFourLines() {
        assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("""
                        {"front": ["a", "b", "c", "d", "e"]}""")));
    }

    @Test
    void rejectsNonStringLine() {
        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("""
                        {"front": ["a", 1, "c", "d"]}""")));
        assertEquals("params[3].front[1]", e.data.get("path"));
    }

    @Test
    void rejectsControlCharacterInLine() {
        JsonObject object = new JsonObject();
        JsonArray front = new JsonArray();
        front.add("a");
        front.add(new JsonPrimitive("b\nc"));
        front.add("d");
        front.add("e");
        object.add("front", front);

        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(object));
        assertEquals("params[3].front[1]", e.data.get("path"));
    }

    @Test
    void acceptsLineAtExactlyMaxCodePoints() throws SignCommands.ValidationException {
        String atLimit = "x".repeat(64);
        JsonObject object = new JsonObject();
        JsonArray front = new JsonArray();
        front.add(atLimit);
        front.add("");
        front.add("");
        front.add("");
        object.add("front", front);

        SignCommands.SignSpec spec = SignCommands.parseSpec(object);
        assertEquals(atLimit, spec.front[0]);
    }

    @Test
    void rejectsLineLongerThanMaxCodePoints() {
        String tooLong = "x".repeat(65);
        JsonObject object = new JsonObject();
        JsonArray front = new JsonArray();
        front.add(tooLong);
        front.add("");
        front.add("");
        front.add("");
        object.add("front", front);

        assertThrows(SignCommands.ValidationException.class, () -> SignCommands.parseSpec(object));
    }

    @Test
    void rejectsNonObjectSpec() {
        assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(JsonParser.parseString("[1,2,3]")));
    }

    // ---- Availability classification (Sign/BlockState are interfaces, so proxies work) ----

    @Test
    void classifiesNonSignBlockStateAsNotASign() {
        BlockState plain = plainBlockState();
        assertEquals(SignCommands.SignAvailability.NOT_A_SIGN, SignCommands.checkAvailability(plain));
    }

    @Test
    void classifiesWaxedSignAsWaxed() {
        Sign waxed = signProxy(true);
        assertEquals(SignCommands.SignAvailability.WAXED, SignCommands.checkAvailability(waxed));
    }

    @Test
    void classifiesUnwaxedSignAsOk() {
        Sign unwaxed = signProxy(false);
        assertEquals(SignCommands.SignAvailability.OK, SignCommands.checkAvailability(unwaxed));
    }

    private static JsonObject spec(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static BlockState plainBlockState() {
        return (BlockState) Proxy.newProxyInstance(
                BlockState.class.getClassLoader(),
                new Class<?>[]{BlockState.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Sign signProxy(boolean waxed) {
        return (Sign) Proxy.newProxyInstance(
                Sign.class.getClassLoader(),
                new Class<?>[]{Sign.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isWaxed" -> waxed;
                    case "toString" -> "SignTestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
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
