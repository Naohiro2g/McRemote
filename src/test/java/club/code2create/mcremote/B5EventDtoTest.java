package club.code2create.mcremote;

import com.google.gson.Gson;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class B5EventDtoTest {
    private static final Gson GSON = new Gson();

    @Test
    void rightClickShapeIsStableAndImmutable() {
        Map<String, Object> dto = B5EventDto.blockRightClick(
                "minecraft:overworld", List.of(200, 0, 200), List.of(1, 2, 3),
                "up", blockValue("minecraft:stone", Map.of()), "main");

        assertEquals(
                "{\"type\":\"block_right_click\",\"dimension\":\"minecraft:overworld\","
                        + "\"origin\":[200,0,200],\"pos\":[1,2,3],\"face\":\"up\","
                        + "\"block\":{\"block_id\":\"minecraft:stone\",\"state\":{}},"
                        + "\"hand\":\"main\"}",
                GSON.toJson(dto));
        assertThrows(UnsupportedOperationException.class, () -> dto.put("x", 1));
    }

    @Test
    void projectilePositionIsCanonicalAtCaptureAndContainsNoBukkitEvent() {
        Map<String, Object> dto = B5EventDto.projectileHit(
                "myworld:world", List.of(0, 0, 0),
                List.of(WireNumbers.position(1.2345), WireNumbers.position(-0.0), WireNumbers.position(-1.2345)),
                "minecraft:arrow", Map.of("kind", "player"));

        assertEquals(
                "{\"type\":\"projectile_hit\",\"dimension\":\"myworld:world\",\"origin\":[0,0,0],"
                        + "\"projectile\":\"minecraft:arrow\",\"pos\":[1.235,0,-1.235],"
                        + "\"target\":{\"kind\":\"player\"}}",
                GSON.toJson(dto));
        assertFalse(containsBukkitEvent(dto));
    }

    @Test
    void chatUsesCanonicalDimensionField() {
        Map<String, Object> dto = B5EventDto.chatPosted(
                "minecraft:the_nether", List.of(4, 5, 6), "hello");

        assertEquals(
                "{\"type\":\"chat_posted\",\"dimension\":\"minecraft:the_nether\","
                        + "\"origin\":[4,5,6],\"message\":\"hello\"}",
                GSON.toJson(dto));
    }

    private static boolean containsBukkitEvent(Object value) {
        if (value instanceof Event) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(B5EventDtoTest::containsBukkitEvent);
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (containsBukkitEvent(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> blockValue(String blockId, Map<String, Object> state) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("block_id", blockId);
        value.put("state", state);
        return value;
    }
}
