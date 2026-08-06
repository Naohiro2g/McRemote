package club.code2create.mcremote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.block.data.BlockData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 稼働中サーバ registry から b3 resource catalog を一度生成して保持する。
 *
 * <p>wire-format-design §7.2.1（DECISIONS 2026-07-29-04）:
 * block/entity/particle を単一 response で返し、catalog 本体を再帰的キーソート・compact JSON 化した
 * UTF-8 bytes の SHA-256 hex を {@code catalogHash} とする。</p>
 */
public final class CatalogService {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Comparator<Object> STATE_VALUE_ORDER =
            Comparator.comparingInt(CatalogService::stateValueRank)
                    .thenComparing(CatalogService::compareStateValues);

    private final Map<String, Object> body;
    private final Map<String, Object> response;
    private final String catalogHash;
    private final int serializedBytes;

    public CatalogService() {
        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("block", buildBlockCatalog());
        generated.put("entity", buildKeyCatalog(Registry.ENTITY_TYPE));
        generated.put("particle", buildKeyCatalog(Registry.PARTICLE_TYPE));
        this.body = generated;

        String canonicalBody = GSON.toJson(canonicalize(generated));
        this.catalogHash = sha256Hex(canonicalBody.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> fullResponse = new LinkedHashMap<>();
        fullResponse.put("catalogHash", catalogHash);
        fullResponse.putAll(body);
        this.response = fullResponse;
        this.serializedBytes = GSON.toJson(fullResponse).getBytes(StandardCharsets.UTF_8).length;
    }

    public String getCatalogHash() {
        return catalogHash;
    }

    public Map<String, Object> getResponse() {
        return response;
    }

    public int getBlockCount() {
        return sizeOf("block");
    }

    public int getEntityCount() {
        return sizeOf("entity");
    }

    public int getParticleCount() {
        return sizeOf("particle");
    }

    public int getSerializedBytes() {
        return serializedBytes;
    }

    /** invalid_property_value の data.allowed に使う、catalog と同じ JSON native value 一覧。 */
    @SuppressWarnings("unchecked")
    public List<Object> getAllowedBlockStateValues(String blockKey, String property) {
        Object blockObject = body.get("block");
        if (!(blockObject instanceof Map<?, ?> blocks)) {
            return List.of();
        }
        Object entryObject = blocks.get(blockKey);
        if (!(entryObject instanceof Map<?, ?> entry)) {
            return List.of();
        }
        Object statesObject = entry.get("states");
        if (!(statesObject instanceof Map<?, ?> states)) {
            return List.of();
        }
        Object allowedObject = states.get(property);
        if (!(allowedObject instanceof List<?> allowed)) {
            return List.of();
        }
        return List.copyOf((List<Object>) allowed);
    }

    private int sizeOf(String key) {
        Object value = body.get(key);
        return value instanceof Map<?, ?> map ? map.size() : 0;
    }

    private Map<String, Object> buildBlockCatalog() {
        Map<String, Object> blocks = new TreeMap<>();
        for (BlockType blockType : Registry.BLOCK) {
            Map<String, List<Object>> allowedValues = new TreeMap<>();
            for (BlockData state : blockType.createBlockDataStates()) {
                for (Map.Entry<String, Object> property : parseProperties(state.getAsString()).entrySet()) {
                    List<Object> values = allowedValues.computeIfAbsent(
                            property.getKey(), ignored -> new ArrayList<>());
                    if (!values.contains(property.getValue())) {
                        values.add(property.getValue());
                    }
                }
            }
            allowedValues.values().forEach(values -> values.sort(STATE_VALUE_ORDER));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("states", allowedValues);
            entry.put("default_state", parseProperties(blockType.createBlockData().getAsString()));
            blocks.put(blockType.getKey().toString(), entry);
        }
        return blocks;
    }

    private <T extends org.bukkit.Keyed> Map<String, Object> buildKeyCatalog(Registry<T> registry) {
        Map<String, Object> entries = new TreeMap<>();
        for (T value : registry) {
            entries.put(value.getKey().toString(), Map.of());
        }
        return entries;
    }

    /**
     * {@code minecraft:id[prop=value,...]} の full state 部分を JSON native value の map にする。
     */
    static Map<String, Object> parseProperties(String blockData) {
        Map<String, Object> properties = new TreeMap<>();
        int open = blockData.indexOf('[');
        if (open < 0) {
            return properties;
        }
        int close = blockData.lastIndexOf(']');
        if (close <= open + 1) {
            return properties;
        }
        String body = blockData.substring(open + 1, close);
        for (String pair : body.split(",")) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals == pair.length() - 1) {
                throw new IllegalArgumentException("Malformed BlockData property: " + blockData);
            }
            String key = pair.substring(0, equals).trim();
            String rawValue = pair.substring(equals + 1).trim();
            properties.put(key, toJsonNativeValue(rawValue));
        }
        return properties;
    }

    private static Object toJsonNativeValue(String value) {
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static int stateValueRank(Object value) {
        if (value instanceof Boolean) {
            return 0;
        }
        if (value instanceof Number) {
            return 1;
        }
        return 2;
    }

    private static int compareStateValues(Object left, Object right) {
        if (left instanceof Boolean l && right instanceof Boolean r) {
            return Boolean.compare(l, r);
        }
        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue());
        }
        return left.toString().compareTo(right.toString());
    }

    /**
     * hash input の map key だけを全階層でソートする。array の順序は catalog 生成側で安定化済み。
     */
    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(entry.getKey().toString(), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            for (Object item : iterable) {
                items.add(canonicalize(item));
            }
            return items;
        }
        return value;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
