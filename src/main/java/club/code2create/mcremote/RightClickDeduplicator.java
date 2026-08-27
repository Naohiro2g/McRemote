package club.code2create.mcremote;

import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Normalizes duplicate Bukkit right-click callbacks for the same player/block within one server
 * tick. This originally only collapsed the known main/off-hand double-fire, but live-human
 * testing (2026-08-26) showed Bukkit can also fire PlayerInteractEvent twice for a single real
 * click on the *same* hand — so any second callback at the same position in the same tick is
 * rejected regardless of hand, not just a same-tick opposite-hand one.
 */
final class RightClickDeduplicator {
    private final Map<Key, Seen> seen = new HashMap<>();

    synchronized boolean accept(
            UUID player,
            String dimension,
            int x,
            int y,
            int z,
            long tick,
            EquipmentSlot hand
    ) {
        Key key = new Key(player, dimension, x, y, z);
        Seen previous = seen.get(key);
        if (previous != null && previous.tick() == tick) {
            return false;
        }
        seen.put(key, new Seen(tick, hand));
        if (seen.size() > 256) {
            seen.entrySet().removeIf(entry -> entry.getValue().tick() < tick - 1);
        }
        return true;
    }

    private record Key(UUID player, String dimension, int x, int y, int z) {
    }

    private record Seen(long tick, EquipmentSlot hand) {
    }
}
