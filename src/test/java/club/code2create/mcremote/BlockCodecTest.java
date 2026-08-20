package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockCodecTest {
    private static final Map<String, Map<String, List<Object>>> BLOCKS = Map.of(
            "minecraft:stone", Map.of(),
            "minecraft:oak_log", Map.of("axis", List.of("x", "y", "z")),
            "minecraft:wheat", Map.of("age", List.of(0, 1, 2, 3, 4, 5, 6, 7)),
            "example:machine", Map.of("powered", List.of(false, true))
    );

    @Test
    void acceptsShortIdPartialStateAndNativeNumber() throws Exception {
        AtomicReference<String> serialized = new AtomicReference<>();
        BlockCodec codec = codec(serialized);

        codec.decode(JsonParser.parseString(
                "{\"block_id\":\"oak_log\",\"state\":{\"axis\":\"z\"}}"), "params[3]");
        assertEquals("minecraft:oak_log[axis=z]", serialized.get());

        codec.decode(JsonParser.parseString(
                "{\"block_id\":\"wheat\",\"state\":{\"age\":3.0}}"), "params[3]");
        assertEquals("minecraft:wheat[age=3]", serialized.get());
    }

    @Test
    void emptyStateCreatesDefaultDataWithoutExistingStateMerge() throws Exception {
        AtomicReference<String> serialized = new AtomicReference<>();
        codec(serialized).decode(JsonParser.parseString(
                "{\"block_id\":\"stone\",\"state\":{}}"), "params[3]");
        assertEquals("minecraft:stone", serialized.get());
    }

    @Test
    void preservesFullyQualifiedNonVanillaIdAndBooleanType() throws Exception {
        AtomicReference<String> serialized = new AtomicReference<>();
        codec(serialized).decode(JsonParser.parseString(
                "{\"block_id\":\"example:machine\",\"state\":{\"powered\":true}}"),
                "params[3]");
        assertEquals("example:machine[powered=true]", serialized.get());

        BlockCodec.ValidationException wrongType = exception(codec(new AtomicReference<>()),
                "{\"block_id\":\"example:machine\",\"state\":{\"powered\":\"true\"}}");
        assertEquals("invalid_property_value", wrongType.reason);
    }

    @Test
    void rejectsLegacyStringMissingUnknownAndNonScalarShapes() {
        BlockCodec codec = codec(new AtomicReference<>());
        assertReason("invalid_params", codec, "\"stone\"");
        assertReason("invalid_params", codec, "{\"block_id\":\"stone\"}");
        assertReason("invalid_params", codec,
                "{\"block_id\":\"stone\",\"state\":{},\"ref\":\"stone\"}");
        assertReason("invalid_params", codec,
                "{\"block_id\":\"oak_log\",\"state\":{\"axis\":[\"z\"]}}");
    }

    @Test
    void emitsOnlyUnambiguousDataPaths() {
        BlockCodec codec = codec(new AtomicReference<>());

        BlockCodec.ValidationException missing = exception(codec,
                "{\"block_id\":\"stone\"}");
        assertEquals("params[3].state", missing.data.get("path"));

        BlockCodec.ValidationException safeUnknown = exception(codec,
                "{\"block_id\":\"stone\",\"state\":{},\"extra_field\":true}");
        assertEquals("params[3].extra_field", safeUnknown.data.get("path"));

        BlockCodec.ValidationException ambiguousUnknown = exception(codec,
                "{\"block_id\":\"stone\",\"state\":{},\"bad.field\":true}");
        assertFalse(ambiguousUnknown.data.containsKey("path"));
    }

    @Test
    void exposesStructuredBlockValidationDataWithoutRef() {
        BlockCodec codec = codec(new AtomicReference<>());

        BlockCodec.ValidationException unknownBlock = exception(codec,
                "{\"block_id\":\"missing\",\"state\":{}}");
        assertEquals("unknown_block", unknownBlock.reason);
        assertEquals("minecraft:missing", unknownBlock.data.get("block_id"));
        assertFalse(unknownBlock.data.containsKey("ref"));

        BlockCodec.ValidationException unknownProperty = exception(codec,
                "{\"block_id\":\"stone\",\"state\":{\"axis\":\"z\"}}");
        assertEquals("unknown_property", unknownProperty.reason);
        assertEquals("axis", unknownProperty.data.get("property"));

        BlockCodec.ValidationException invalidValue = exception(codec,
                "{\"block_id\":\"oak_log\",\"state\":{\"axis\":\"w\"}}");
        assertEquals("invalid_property_value", invalidValue.reason);
        assertEquals(List.of("x", "y", "z"), invalidValue.data.get("allowed"));
        assertEquals("w", invalidValue.data.get("value"));
    }

    @Test
    void emitsFullyQualifiedBlockValueWithFullSortedState() {
        Map<String, Object> value = BlockCodec.encode(blockData(
                "minecraft:oak_stairs[waterlogged=true,shape=straight,half=top,facing=east]"));

        assertEquals("minecraft:oak_stairs", value.get("block_id"));
        assertEquals(Map.of(
                "facing", "east", "half", "top", "shape", "straight", "waterlogged", true),
                value.get("state"));
        assertThrows(UnsupportedOperationException.class, () -> value.put("block_id", "minecraft:air"));
    }

    private static BlockCodec codec(AtomicReference<String> serialized) {
        return new BlockCodec(BLOCKS::get, value -> {
            serialized.set(value);
            return blockData(value);
        });
    }

    private static void assertReason(String reason, BlockCodec codec, String json) {
        assertEquals(reason, exception(codec, json).reason);
    }

    private static BlockCodec.ValidationException exception(BlockCodec codec, String json) {
        return assertThrows(BlockCodec.ValidationException.class,
                () -> codec.decode(JsonParser.parseString(json), "params[3]"));
    }

    private static BlockData blockData(String serialized) {
        return (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAsString" -> serialized;
                    case "toString" -> serialized;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
