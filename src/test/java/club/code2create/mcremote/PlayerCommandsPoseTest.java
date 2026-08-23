package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import org.bukkit.Location;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCommandsPoseTest {
    @Test
    void parsesFinitePoseAndNormalizesYaw() {
        PlayerCommands.PoseInput pose = PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "1.25", "-2.5", "3", "541", "-45.5"});

        assertEquals(1.25, pose.relativeX());
        assertEquals(-2.5, pose.relativeY());
        assertEquals(3.0, pose.relativeZ());
        assertEquals(-179.0f, pose.yaw());
        assertEquals(-45.5f, pose.pitch());
    }

    @Test
    void acceptsInclusivePitchBoundaries() {
        assertEquals(-90.0f, PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "0", "0", "0", "0", "-90"}).pitch());
        assertEquals(90.0f, PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "0", "0", "0", "0", "90"}).pitch());
    }

    @Test
    void rejectsOutOfRangePitch() {
        assertThrows(IllegalArgumentException.class, () -> PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "0", "0", "0", "0", "90.0001"}));
        assertThrows(IllegalArgumentException.class, () -> PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "0", "0", "0", "0", "-90.0001"}));
    }

    @Test
    void rejectsMalformedAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "0", "0", "0", "not-a-number", "0"}));
        assertThrows(IllegalArgumentException.class, () -> PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "NaN", "0", "0", "0", "0"}));
        assertThrows(IllegalArgumentException.class, () -> PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "0", "Infinity", "0", "0", "0"}));
        assertThrows(IllegalArgumentException.class, () -> PlayerCommands.parsePoseInput(
                new String[]{"minecraft:overworld", "0", "0", "0", "0"}));
    }

    @Test
    void yawUsesMinecraftNormalRange() {
        assertEquals(-180.0f, PlayerCommands.normalizeYaw(180.0));
        assertEquals(-180.0f, PlayerCommands.normalizeYaw(-180.0));
        assertEquals(-179.0f, PlayerCommands.normalizeYaw(181.0));
        assertEquals(179.0f, PlayerCommands.normalizeYaw(-181.0));
        assertEquals(0.0f, PlayerCommands.normalizeYaw(720.0));
    }

    @Test
    void finiteGuardRejectsOverflowResults() {
        assertTrue(PlayerCommands.areFinite(0.0, Double.MAX_VALUE));
        assertFalse(PlayerCommands.areFinite(Double.POSITIVE_INFINITY));
        assertFalse(PlayerCommands.areFinite(Double.NaN));
        assertFalse(PlayerCommands.areFinite(Double.MAX_VALUE + Double.MAX_VALUE));
    }

    @Test
    void positionAndPoseExposeCanonicalDimensionOnly() {
        var world = DimensionKeyContractTest.world("myworld:world");
        Location origin = new Location(world, 10, 20, 30);
        Location actual = new Location(world, 11.2345, 18.7655, 30, 181.0f, -45.555f);

        Map<String, Object> position = PlayerCommands.positionResult(actual, origin);
        assertEquals(List.of("dimension", "pos"), List.copyOf(position.keySet()));
        assertEquals("myworld:world", position.get("dimension"));
        assertEquals(List.of(new BigDecimal("1.235"), new BigDecimal("-1.235"), BigDecimal.ZERO),
                position.get("pos"));

        Map<String, Object> pose = PlayerCommands.poseResult(actual, origin);
        assertEquals(List.of("dimension", "pos", "yaw", "pitch"), List.copyOf(pose.keySet()));
        assertEquals(new BigDecimal("-179"), pose.get("yaw"));
        assertEquals(new BigDecimal("-45.56"), pose.get("pitch"));
    }
}
