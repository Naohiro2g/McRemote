package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockQueryRegionTest {
    @Test
    void normalizesReversedEndpointsAndTraversesXThenYThenZ() throws Exception {
        BlockQueryRegion region = BlockQueryRegion.parse(
                JsonParser.parseString("[1,1,1,0,0,0]"));

        assertEquals(List.of(
                new BlockQueryRegion.Position(0, 0, 0),
                new BlockQueryRegion.Position(0, 0, 1),
                new BlockQueryRegion.Position(0, 1, 0),
                new BlockQueryRegion.Position(0, 1, 1),
                new BlockQueryRegion.Position(1, 0, 0),
                new BlockQueryRegion.Position(1, 0, 1),
                new BlockQueryRegion.Position(1, 1, 0),
                new BlockQueryRegion.Position(1, 1, 1)),
                region.positions());
    }

    @Test
    void acceptsExactTenCubedBoundary() throws Exception {
        BlockQueryRegion region = BlockQueryRegion.between(9, 9, 9, 0, 0, 0);

        assertEquals(BlockQueryRegion.MAX_BLOCKS, region.size());
        assertEquals(new BlockQueryRegion.Position(0, 0, 0), region.positions().get(0));
        assertEquals(new BlockQueryRegion.Position(9, 9, 9), region.positions().get(999));
    }

    @Test
    void rejectsElevenOnEveryAxis() {
        assertThrows(BlockQueryRegion.WorkLimitExceededException.class,
                () -> BlockQueryRegion.between(0, 0, 0, 10, 0, 0));
        assertThrows(BlockQueryRegion.WorkLimitExceededException.class,
                () -> BlockQueryRegion.between(0, 0, 0, 0, 10, 0));
        assertThrows(BlockQueryRegion.WorkLimitExceededException.class,
                () -> BlockQueryRegion.between(0, 0, 0, 0, 0, 10));
    }

    @Test
    void rejectsBadShapeFractionAndOriginOverflowBeforeTraversal() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> BlockQueryRegion.parse(JsonParser.parseString("[0,0,0,1,1]")));
        assertThrows(IllegalArgumentException.class,
                () -> BlockQueryRegion.parse(JsonParser.parseString("[0,0,0,1.5,1,1]")));

        BlockQueryRegion region = BlockQueryRegion.between(
                Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> region.translate(1, 0, 0));
    }
}
