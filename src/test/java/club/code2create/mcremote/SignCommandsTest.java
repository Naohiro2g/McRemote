package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignCommandsTest {

    // ---- SignSpec parsing (pure, no Bukkit types) ----

    @Test
    void acceptsFrontOnlyAndLeavesBackNull() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"front": ["a", "b", "c", "d"]}"""));
        assertLineTexts(new String[]{"a", "b", "c", "d"}, spec.front);
        assertNull(spec.back);
    }

    @Test
    void acceptsBackOnlyAndLeavesFrontNull() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"back": ["1", "2", "3", "4"]}"""));
        assertNull(spec.front);
        assertLineTexts(new String[]{"1", "2", "3", "4"}, spec.back);
    }

    @Test
    void acceptsBothFacesIndependently() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"front": ["a", "", "", ""], "back": ["b", "", "", ""]}"""));
        assertEquals("a", spec.front[0].text);
        assertEquals("b", spec.back[0].text);
    }

    @Test
    void plainStringLineDefaultsToNoColorAndNoDecoration() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"front": ["hello", "", "", ""]}"""));
        SignCommands.LineSpec line = spec.front[0];
        assertEquals("hello", line.text);
        assertNull(line.color);
        assertFalse(line.bold);
        assertFalse(line.italic);
    }

    @Test
    void objectLineAcceptsNamedColorAndDecorations() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"front": [{"text": "hi", "color": "red", "bold": true, "italic": true}, "", "", ""]}"""));
        SignCommands.LineSpec line = spec.front[0];
        assertEquals("hi", line.text);
        assertEquals(NamedTextColor.RED, line.color);
        assertTrue(line.bold);
        assertTrue(line.italic);
    }

    @Test
    void objectLineAcceptsHexColor() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"front": [{"text": "hi", "color": "#ff0000"}, "", "", ""]}"""));
        assertEquals(TextColor.fromHexString("#ff0000"), spec.front[0].color);
    }

    @Test
    void objectLineOmittingBoldAndItalicDefaultsToFalse() throws SignCommands.ValidationException {
        SignCommands.SignSpec spec = SignCommands.parseSpec(spec("""
                {"front": [{"text": "hi", "color": "blue"}, "", "", ""]}"""));
        SignCommands.LineSpec line = spec.front[0];
        assertFalse(line.bold);
        assertFalse(line.italic);
    }

    @Test
    void rejectsUnknownColorToken() {
        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("""
                        {"front": [{"text": "hi", "color": "not_a_color"}, "", "", ""]}""")));
        assertEquals("invalid_property_value", e.reason);
        assertEquals("color", e.data.get("property"));
        assertEquals("not_a_color", e.data.get("value"));
    }

    @Test
    void rejectsNonBooleanBold() {
        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("""
                        {"front": [{"text": "hi", "bold": "yes"}, "", "", ""]}""")));
        assertEquals("invalid_params", e.reason);
        assertEquals("params[3].front[0].bold", e.data.get("path"));
    }

    @Test
    void rejectsUnknownLineObjectField() {
        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("""
                        {"front": [{"text": "hi", "click_event": "x"}, "", "", ""]}""")));
        assertEquals("invalid_params", e.reason);
        assertEquals("params[3].front[0].click_event", e.data.get("path"));
    }

    @Test
    void rejectsLineObjectMissingText() {
        SignCommands.ValidationException e = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(spec("""
                        {"front": [{"color": "red"}, "", "", ""]}""")));
        assertEquals("params[3].front[0].text", e.data.get("path"));
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
        assertEquals(atLimit, spec.front[0].text);
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

    // ---- getSign encoding (pure Adventure Component logic, no Bukkit server needed) ----

    @Test
    void encodeColorReturnsDefaultTokenForUnsetColor() {
        assertEquals("black", SignCommands.encodeColor(null));
    }

    @Test
    void encodeColorReturnsNamedTokenForNamedColor() {
        assertEquals("red", SignCommands.encodeColor(NamedTextColor.RED));
    }

    @Test
    void encodeColorReturnsNamedTokenForColorMatchingNamedRgbByValue() {
        // Not a NamedTextColor instance, but its RGB exactly equals RED's — still canonicalizes to "red".
        TextColor plainRed = TextColor.color(NamedTextColor.RED.value());
        assertEquals("red", SignCommands.encodeColor(plainRed));
    }

    @Test
    void encodeColorReturnsHexForNonStandardColor() {
        TextColor custom = TextColor.fromHexString("#123456");
        assertEquals("#123456", SignCommands.encodeColor(custom));
    }

    @Test
    void encodeLineProducesFullCanonicalMap() {
        net.kyori.adventure.text.Component component = net.kyori.adventure.text.Component.text("hi")
                .color(NamedTextColor.BLUE)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true);
        var value = SignCommands.encodeLine(component);
        assertEquals("hi", value.get("text"));
        assertEquals("blue", value.get("color"));
        assertEquals(true, value.get("bold"));
        assertEquals(false, value.get("italic"));
    }

    @Test
    void encodeLineTreatsUnsetDecorationAsFalse() {
        // A component we never touched (e.g. a pre-existing player-placed sign line) has
        // TextDecoration.State.NOT_SET, which must canonicalize to false, not be left ambiguous.
        net.kyori.adventure.text.Component component = net.kyori.adventure.text.Component.text("hi");
        var value = SignCommands.encodeLine(component);
        assertEquals(false, value.get("bold"));
        assertEquals(false, value.get("italic"));
        assertEquals("black", value.get("color"));
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

    @Test
    void encodeReturnsFullFrontBackAndWaxedShape() {
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.text("hi");
        Sign sign = signWithLines(true, line);
        var result = SignCommands.encode(sign);
        assertEquals(true, result.get("waxed"));
        @SuppressWarnings("unchecked")
        var front = (java.util.List<Object>) result.get("front");
        @SuppressWarnings("unchecked")
        var back = (java.util.List<Object>) result.get("back");
        assertEquals(4, front.size());
        assertEquals(4, back.size());
    }

    private static JsonObject spec(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void assertLineTexts(String[] expected, SignCommands.LineSpec[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i].text);
        }
    }

    private static Sign signWithLines(boolean waxed, net.kyori.adventure.text.Component line) {
        org.bukkit.block.sign.SignSide side = (org.bukkit.block.sign.SignSide) Proxy.newProxyInstance(
                org.bukkit.block.sign.SignSide.class.getClassLoader(),
                new Class<?>[]{org.bukkit.block.sign.SignSide.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "line" -> line;
                    default -> defaultValue(method.getReturnType());
                });
        return (Sign) Proxy.newProxyInstance(
                Sign.class.getClassLoader(),
                new Class<?>[]{Sign.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isWaxed" -> waxed;
                    case "getSide" -> side;
                    default -> defaultValue(method.getReturnType());
                });
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
