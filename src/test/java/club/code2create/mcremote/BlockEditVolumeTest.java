package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockEditVolumeTest {
    @Test
    void countsInclusiveCuboidIndependentOfEndpointOrder() {
        assertEquals(1L, BlockEditVolume.between(0, 0, 0, 0, 0, 0));
        assertEquals(24L, BlockEditVolume.between(0, 0, 0, 1, 2, 3));
        assertEquals(24L, BlockEditVolume.between(1, 2, 3, 0, 0, 0));
    }

    @Test
    void calculatesSupportedCoordinateExtremesWithoutNarrowing() {
        assertEquals(8_004_008_004_002_001L,
                BlockEditVolume.between(-1_000_000, -1_000, -1_000_000,
                        1_000_000, 1_000, 1_000_000));
    }
}
