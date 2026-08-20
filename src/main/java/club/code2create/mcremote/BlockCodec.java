package club.code2create.mcremote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** protocol 22 BlockSpec validation and BlockValue canonicalization. */
final class BlockCodec {
    private final Function<String, Map<String, List<Object>>> stateLookup;
    private final Function<String, BlockData> blockDataFactory;

    BlockCodec(CatalogService catalogService) {
        this(catalogService::getBlockStateValues, Bukkit::createBlockData);
    }

    BlockCodec(
            Function<String, Map<String, List<Object>>> stateLookup,
            Function<String, BlockData> blockDataFactory
    ) {
        this.stateLookup = stateLookup;
        this.blockDataFactory = blockDataFactory;
    }

    BlockData decode(JsonElement element, String path) throws ValidationException {
        if (element == null || !element.isJsonObject()) {
            throw invalid(path, "BlockSpec must be an object");
        }
        JsonObject spec = element.getAsJsonObject();
        for (String key : spec.keySet()) {
            if (!"block_id".equals(key) && !"state".equals(key)) {
                throw invalidField(path, key, "unknown BlockSpec field");
            }
        }
        if (!spec.has("block_id")) {
            throw invalid(path + ".block_id", "missing block_id");
        }
        if (!spec.has("state")) {
            throw invalid(path + ".state", "missing state");
        }

        JsonElement blockIdElement = spec.get("block_id");
        if (blockIdElement == null || !blockIdElement.isJsonPrimitive()
                || !blockIdElement.getAsJsonPrimitive().isString()) {
            throw invalid(path + ".block_id", "block_id must be a string");
        }
        String inputBlockId = blockIdElement.getAsString();
        String blockId = inputBlockId.contains(":") ? inputBlockId : "minecraft:" + inputBlockId;
        Map<String, List<Object>> definitions = stateLookup.apply(blockId);
        if (definitions == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("block_id", blockId);
            throw new ValidationException("unknown_block", data);
        }

        JsonElement stateElement = spec.get("state");
        if (stateElement == null || !stateElement.isJsonObject()) {
            throw invalid(path + ".state", "state must be an object");
        }
        Map<String, Object> canonicalState = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : stateElement.getAsJsonObject().entrySet()) {
            String property = entry.getKey();
            String propertyPath = path + ".state." + property;
            List<Object> allowed = definitions.get(property);
            if (allowed == null) {
                Map<String, Object> data = blockPropertyData(blockId, property);
                throw new ValidationException("unknown_property", data);
            }
            Object inputValue = scalar(entry.getValue(), propertyPath);
            Object acceptedValue = findAllowed(inputValue, allowed);
            if (acceptedValue == null) {
                Map<String, Object> data = blockPropertyData(blockId, property);
                data.put("value", inputValue);
                data.put("allowed", allowed);
                throw new ValidationException("invalid_property_value", data);
            }
            canonicalState.put(property, acceptedValue);
        }

        StringBuilder serialized = new StringBuilder(blockId);
        if (!canonicalState.isEmpty()) {
            serialized.append('[');
            boolean first = true;
            for (Map.Entry<String, Object> entry : canonicalState.entrySet()) {
                if (!first) {
                    serialized.append(',');
                }
                serialized.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            serialized.append(']');
        }
        try {
            return blockDataFactory.apply(serialized.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("catalog and live block registry disagree for " + blockId, e);
        }
    }

    static Map<String, Object> encode(BlockData data) {
        String serialized = data.getAsString();
        int stateStart = serialized.indexOf('[');
        String blockId = stateStart < 0 ? serialized : serialized.substring(0, stateStart);
        Map<String, Object> state = Collections.unmodifiableMap(
                new LinkedHashMap<>(CatalogService.parseProperties(serialized)));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("block_id", blockId);
        value.put("state", state);
        return Collections.unmodifiableMap(value);
    }

    private static ValidationException invalid(String path, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return new ValidationException("invalid_params", data, detail);
    }

    private static ValidationException invalidField(String parentPath, String field, String detail) {
        if (field.matches("[A-Za-z0-9_]+")) {
            return invalid(parentPath + "." + field, detail);
        }
        return new ValidationException("invalid_params", Map.of(), detail);
    }

    private static Map<String, Object> blockPropertyData(String blockId, String property) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block_id", blockId);
        data.put("property", property);
        return data;
    }

    private static Object scalar(JsonElement element, String path) throws ValidationException {
        if (element == null || !element.isJsonPrimitive()) {
            throw invalid(path, "state value must be a JSON scalar");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            try {
                return primitive.getAsBigDecimal();
            } catch (NumberFormatException e) {
                throw invalid(path, "state number is invalid");
            }
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        throw invalid(path, "state value must be boolean, number, or string");
    }

    private static Object findAllowed(Object input, List<Object> allowed) {
        for (Object candidate : allowed) {
            if (sameScalar(input, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean sameScalar(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return left != null && right != null && left.getClass() == right.getClass() && left.equals(right);
    }

    static final class ValidationException extends Exception {
        final String reason;
        final Map<String, Object> data;

        ValidationException(String reason, Map<String, Object> data) {
            this(reason, data, reason);
        }

        ValidationException(String reason, Map<String, Object> data, String detail) {
            super(detail);
            this.reason = reason;
            this.data = Map.copyOf(data);
        }
    }
}
