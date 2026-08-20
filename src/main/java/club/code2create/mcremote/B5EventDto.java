package club.code2create.mcremote;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, Bukkit-object-free event DTO fixtures for the b5 wire contract. */
final class B5EventDto {
    private B5EventDto() {
    }

    static Map<String, Object> blockRightClick(
            String world,
            List<Integer> origin,
            List<Integer> position,
            String face,
            Map<String, Object> block,
            String hand
    ) {
        Map<String, Object> dto = common("block_right_click", world, origin);
        dto.put("pos", List.copyOf(position));
        dto.put("face", face);
        dto.put("block", immutable(block));
        dto.put("hand", hand);
        return immutable(dto);
    }

    static Map<String, Object> chatPosted(
            String world,
            List<Integer> origin,
            String message
    ) {
        Map<String, Object> dto = common("chat_posted", world, origin);
        dto.put("message", message);
        return immutable(dto);
    }

    static Map<String, Object> projectileHit(
            String world,
            List<Integer> origin,
            List<BigDecimal> position,
            String projectile,
            Map<String, Object> target
    ) {
        Map<String, Object> dto = common("projectile_hit", world, origin);
        dto.put("projectile", projectile);
        dto.put("pos", List.copyOf(position));
        dto.put("target", immutable(target));
        return immutable(dto);
    }

    private static Map<String, Object> common(String type, String world, List<Integer> origin) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("type", type);
        dto.put("world", world);
        dto.put("origin", List.copyOf(origin));
        return dto;
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
