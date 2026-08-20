package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiscCommandsHeightTest {
    @Test
    void findsHighestExposedBlockFromWorldBuildLimit() {
        IntPredicate passable = passableExcept(Set.of(10, 9, 8));

        assertEquals(OptionalInt.of(10),
                MiscCommands.findHighestExposedBlockY(-64, 319, 319, passable));
    }

    @Test
    void maxYFindsNextExposedLayerWithoutReturningSolidInterior() {
        IntPredicate passable = passableExcept(Set.of(
                20, 19, 18,
                12, 11,
                4, 3, 2, 1, 0));

        OptionalInt first = MiscCommands.findHighestExposedBlockY(-64, 319, 319, passable);
        OptionalInt second = MiscCommands.findHighestExposedBlockY(-64, 319, first.orElseThrow() - 1, passable);
        OptionalInt third = MiscCommands.findHighestExposedBlockY(-64, 319, second.orElseThrow() - 1, passable);

        assertEquals(OptionalInt.of(20), first);
        assertEquals(OptionalInt.of(12), second);
        assertEquals(OptionalInt.of(4), third);
    }

    @Test
    void maxYInsideSolidLayerSkipsThatLayer() {
        IntPredicate passable = passableExcept(Set.of(20, 19, 18, 12, 11));

        assertEquals(OptionalInt.of(12),
                MiscCommands.findHighestExposedBlockY(-64, 319, 19, passable));
    }

    @Test
    void clampsMaxYToWorldBuildLimit() {
        IntPredicate passable = passableExcept(Set.of(319));

        assertEquals(OptionalInt.of(319),
                MiscCommands.findHighestExposedBlockY(-64, 319, 10_000, passable));
    }

    @Test
    void returnsEmptyBelowWorldMinimumOrWhenNoSurfaceExists() {
        IntPredicate allPassable = y -> true;

        assertTrue(MiscCommands.findHighestExposedBlockY(-64, 319, -65, allPassable).isEmpty());
        assertTrue(MiscCommands.findHighestExposedBlockY(-64, 319, 319, allPassable).isEmpty());
    }

    private static IntPredicate passableExcept(Set<Integer> nonPassableY) {
        return y -> !nonPassableY.contains(y);
    }
}
