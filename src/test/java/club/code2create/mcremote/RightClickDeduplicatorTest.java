package club.code2create.mcremote;

import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RightClickDeduplicatorTest {
    @Test
    void collapsesCorrelatedMainAndOffHandCallbacksOnly() {
        RightClickDeduplicator deduplicator = new RightClickDeduplicator();
        UUID player = UUID.randomUUID();

        assertTrue(deduplicator.accept(player, "minecraft:overworld", 1, 2, 3, 10, EquipmentSlot.HAND));
        assertFalse(deduplicator.accept(player, "minecraft:overworld", 1, 2, 3, 10, EquipmentSlot.OFF_HAND));
        assertTrue(deduplicator.accept(player, "minecraft:overworld", 1, 2, 3, 11, EquipmentSlot.OFF_HAND));
        assertTrue(deduplicator.accept(player, "minecraft:overworld", 2, 2, 3, 11, EquipmentSlot.HAND));
    }
}
