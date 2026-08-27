package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fixture-driven contract test against the exact bytes of scratch-editor's
 * mc-remote/protocol/test/fixtures/sign-v23.json (agent/b6-source-refresh@104f194d), the fixture
 * DECISIONS 2026-08-26-05 anchors as the b6 sign exact contract. Drives McRemote's production
 * parser/encoder from the same fixture the Scratch client tests against, instead of duplicating
 * fixture values as separate Java literals (see also SignCommandsTest for hand-written edge cases
 * this fixture does not cover).
 */
class SignFixtureContractTest {
    private static final String FIXTURE = "/fixtures/sign-v23.json";

    @Test
    void protocolAndMethodNamesMatchFixture() {
        JsonObject fixture = fixture();
        assertEquals("23.0.0", fixture.get("protocol").getAsString());
        JsonObject methods = fixture.getAsJsonObject("methods");
        assertEquals("world.getSign", methods.get("get").getAsString());
        assertEquals("world.setSign", methods.get("set").getAsString());
        assertEquals("world.updateSignLine", methods.get("update_line").getAsString());
    }

    // B6-S01: SignCommands.parseSpec against every line_specs.B6-S01 shorthand/object variant.
    @Test
    void b6S01ParseSpecAcceptsEachFixtureLineSpecVariant() throws SignCommands.ValidationException {
        JsonObject variants = fixture().getAsJsonObject("line_specs").getAsJsonObject("B6-S01");

        // Input decorations are an unordered set at parse time — canonical (sorted) order is an
        // encodeLine/getSign output property (B6-S02), not a parseSpec input requirement — so these
        // compare as sets, not ordered lists.
        SignCommands.LineSpec shorthand = parseSingleFrontLine(variants.get("string_shorthand"));
        assertEquals("Hello", shorthand.text);
        assertEquals(Set.of(), Set.copyOf(decorationTokens(shorthand)));
        assertEquals("black", SignCommands.encodeColor(shorthand.color));

        JsonObject namedColor = variants.getAsJsonObject("object_named_color");
        SignCommands.LineSpec named = parseSingleFrontLine(namedColor);
        assertEquals(namedColor.get("text").getAsString(), named.text);
        assertEquals(namedColor.get("color").getAsString(), SignCommands.encodeColor(named.color));
        assertEquals(Set.copyOf(jsonStrings(namedColor.getAsJsonArray("decorations"))),
                Set.copyOf(decorationTokens(named)));

        JsonObject hexColor = variants.getAsJsonObject("object_hex_color");
        SignCommands.LineSpec hex = parseSingleFrontLine(hexColor);
        assertEquals(hexColor.get("color").getAsString(), SignCommands.encodeColor(hex.color));
        assertEquals(Set.copyOf(jsonStrings(hexColor.getAsJsonArray("decorations"))),
                Set.copyOf(decorationTokens(hex)));

        JsonObject allDecorations = variants.getAsJsonObject("object_all_decorations");
        SignCommands.LineSpec all = parseSingleFrontLine(allDecorations);
        assertEquals(Set.copyOf(jsonStrings(allDecorations.getAsJsonArray("decorations"))),
                Set.copyOf(decorationTokens(all)));
    }

    // B6-S02: parse -> Component -> SignCommands.encodeLine round trip against line_values.B6-S02.
    @Test
    void b6S02EncodeLineProducesFixtureCanonicalValues() throws SignCommands.ValidationException {
        JsonObject cases = fixture().getAsJsonObject("line_values").getAsJsonObject("B6-S02");

        assertEncodesTo(JsonParser.parseString("\"Hello\""),
                cases.getAsJsonObject("from_string_shorthand"));

        JsonObject namedColorInput = cases.getAsJsonObject("from_object_named_color");
        assertEncodesTo(namedColorInput, namedColorInput);

        JsonObject unsorted = cases.getAsJsonObject("from_object_unsorted_input");
        JsonObject input = new JsonObject();
        input.addProperty("text", unsorted.getAsJsonObject("result").get("text").getAsString());
        input.addProperty("color", unsorted.getAsJsonObject("result").get("color").getAsString());
        input.add("decorations", unsorted.getAsJsonArray("input_decorations"));
        assertEncodesTo(input, unsorted.getAsJsonObject("result"));
    }

    // B6-S04: parseSpec against the full 4-line, both-face set_sign params shape.
    @Test
    void b6S04ParseSpecAcceptsFourLineTwoFaceShape() throws SignCommands.ValidationException {
        JsonArray params = fixture().getAsJsonObject("set_sign").getAsJsonObject("B6-S04")
                .getAsJsonArray("params");
        assertEquals(1, params.get(0).getAsInt());
        assertEquals(2, params.get(1).getAsInt());
        assertEquals(3, params.get(2).getAsInt());

        SignCommands.SignSpec spec = SignCommands.parseSpec(params.get(3));

        assertEquals("Front 1", spec.front[0].text);
        assertEquals("Front 2", spec.front[1].text);
        assertEquals("gold", SignCommands.encodeColor(spec.front[1].color));
        assertEquals(List.of("bold"), decorationTokens(spec.front[1]));
        assertEquals("", spec.front[2].text);
        assertEquals("", spec.front[3].text);

        assertEquals("Back 1", spec.back[0].text);
        assertEquals("", spec.back[1].text);
        assertEquals("", spec.back[2].text);
        assertEquals("", spec.back[3].text);
    }

    // B6-S06: unknown color/decoration tokens fed to the production parser.
    @Test
    void b6S06RejectsUnknownColorAndDecorationTokens() {
        JsonArray cases = fixture().getAsJsonObject("invalid_params").getAsJsonArray("B6-S06");
        JsonObject unknownColor = findCase(cases, "unknown_color_token");
        JsonObject unknownDecoration = findCase(cases, "unknown_decoration_token");

        SignCommands.ValidationException colorError = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(unknownColor.getAsJsonArray("params").get(3)));
        assertEquals("invalid_property_value", colorError.reason);
        JsonObject colorData = unknownColor.getAsJsonObject("data");
        assertEquals(colorData.get("property").getAsString(), colorError.data.get("property"));
        assertEquals(colorData.get("value").getAsString(), colorError.data.get("value"));
        assertEquals(jsonStrings(colorData.getAsJsonArray("allowed")), colorError.data.get("allowed"));

        // updateSignLine validates one LineSpec via the same lineSpec() code path parseSpec calls
        // for each of the 4 array slots, so wrapping it as a single-line face exercises the exact
        // same production validation without needing to widen a private method for this test.
        JsonObject wrapped = new JsonObject();
        JsonArray front = new JsonArray();
        front.add(unknownDecoration.getAsJsonArray("params").get(5));
        front.add("");
        front.add("");
        front.add("");
        wrapped.add("front", front);

        SignCommands.ValidationException decorationError = assertThrows(SignCommands.ValidationException.class,
                () -> SignCommands.parseSpec(wrapped));
        assertEquals("invalid_property_value", decorationError.reason);
        JsonObject decorationData = unknownDecoration.getAsJsonObject("data");
        assertEquals(decorationData.get("property").getAsString(), decorationError.data.get("property"));
        assertEquals(decorationData.get("value").getAsString(), decorationError.data.get("value"));
        assertEquals(jsonStrings(decorationData.getAsJsonArray("allowed")), decorationError.data.get("allowed"));
    }

    private static void assertEncodesTo(JsonElement lineSpecJson, JsonObject expectedLineValue)
            throws SignCommands.ValidationException {
        SignCommands.LineSpec parsed = parseSingleFrontLine(lineSpecJson);
        Component component = SignCommands.componentFor(parsed);
        Map<String, Object> encoded = SignCommands.encodeLine(component);

        assertEquals(expectedLineValue.get("text").getAsString(), encoded.get("text"));
        assertEquals(expectedLineValue.get("color").getAsString(), encoded.get("color"));
        assertEquals(jsonStrings(expectedLineValue.getAsJsonArray("decorations")), encoded.get("decorations"));
    }

    private static SignCommands.LineSpec parseSingleFrontLine(JsonElement lineSpecJson)
            throws SignCommands.ValidationException {
        JsonObject wrapped = new JsonObject();
        JsonArray front = new JsonArray();
        front.add(lineSpecJson);
        front.add("");
        front.add("");
        front.add("");
        wrapped.add("front", front);
        return SignCommands.parseSpec(wrapped).front[0];
    }

    private static List<String> decorationTokens(SignCommands.LineSpec spec) {
        return spec.decorations.stream()
                .map(TextDecoration.NAMES::key)
                .sorted()
                .toList();
    }

    private static JsonObject findCase(JsonArray cases, String caseName) {
        for (JsonElement element : cases) {
            JsonObject candidate = element.getAsJsonObject();
            if (caseName.equals(candidate.get("case").getAsString())) {
                return candidate;
            }
        }
        throw new AssertionError("fixture case not found: " + caseName);
    }

    private static List<String> jsonStrings(JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsString).toList();
    }

    private static JsonObject fixture() {
        try (var reader = new InputStreamReader(
                SignFixtureContractTest.class.getResourceAsStream(FIXTURE), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
