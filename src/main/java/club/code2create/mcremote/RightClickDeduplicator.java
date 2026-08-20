package club.code2create.mcremote;

import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Normalizes Bukkit main/off-hand duplicate right-click callbacks within one server tick. */
final class RightClickDeduplicator {
    private final Map<Key, Seen> seen = new HashMap<>();

    synchronized boolean accept(
            UUID player,
            String world,
            int x,
            int y,
            int z,
            long tick,
            EquipmentSlot hand
    ) {
        Key key = new Key(player, world, x, y, z);
        Seen previous = seen.get(key);
        if (previous != null && previous.tick() == tick && previous.hand() != hand) {
            return false;
        }
        seen.put(key, new Seen(tick, hand));
        if (seen.size() > 256) {
            seen.entrySet().removeIf(entry -> entry.getValue().tick() < tick - 1);
        }
        return true;
    }

    private record Key(UUID player, String world, int x, int y, int z) {
    }

    private record Seen(long tick, EquipmentSlot hand) {
    }
}
