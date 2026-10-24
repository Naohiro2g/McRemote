package club.code2create.mcremote;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HudFrameTest {
    private final World world = mock(World.class);

    @Test
    void bodyAndTargetUseTheSpecifiedAbsoluteRelativeAndTimeColorsEvenWithoutCoordinates() {
        for (HudSampler.Point mode : HudSampler.Point.values()) {
            var rendered = HudFrame.sample(null, null, 1234, mode).render();
            boolean target = mode == HudSampler.Point.TARGET;
            assertEquals(target ? 0xd8d6d5 : 0xa89b94, rendered.color().value());
            assertEquals(target ? 0xf9c7e5 : 0xf15fb6, rendered.children().get(1).color().value());
            assertEquals(0x55c01b, rendered.children().get(3).color().value());
        }
    }

    @Test
    void relativeCoordinatesSubtractAllAxesFromTheSelectedOrigin() {
        HudFrame frame = HudFrame.sample(new Location(world, 1122.8, 120.9, 1133.2),
                new Location(world, 1100, 100, 1100), 23999);
        assertEquals(new HudFrame.Coordinates(1122, 120, 1133), frame.absolute());
        assertEquals(new HudFrame.Coordinates(22, 20, 33), frame.relative());
        assertEquals("1122 120 1133 / 22 20 33 / 23999", text(frame));
    }

    @Test
    void negativePositionsFloorInsteadOfTruncatingAndBodyYDoesNotSubtractTheFloorBlock() {
        HudFrame frame = HudFrame.sample(new Location(world, -0.01, 64, -12.99),
                new Location(world, -200, 3, -200), 0);
        assertEquals(new HudFrame.Coordinates(-1, 64, -13), frame.absolute());
        assertEquals(new HudFrame.Coordinates(199, 61, 187), frame.relative());
    }

    @Test
    void relativeDifferencesDoNotOverflowSignedIntegerCoordinates() {
        HudFrame frame = HudFrame.sample(new Location(world, Integer.MAX_VALUE, 0, 0),
                new Location(world, Integer.MIN_VALUE, 0, 0), 0);
        assertEquals(4_294_967_295L, frame.relative().x());
    }

    @Test
    void noConnectionKeepsAbsolutePositionAndWorldTime() {
        assertEquals("12 64 34 / — / 1234",
                text(HudFrame.sample(new Location(world, 12, 64, 34), null, 1234)));
    }

    @Test
    void anotherDimensionDoesNotShowMisleadingRelativeCoordinates() {
        HudFrame frame = HudFrame.sample(new Location(world, 0, 64, 0),
                new Location(mock(World.class), 200, 0, 200), 1234);
        assertNull(frame.relative());
        assertEquals("0 64 0 / — / 1234", text(frame));
    }

    @Test
    void missingTargetClearsBothCoordinateFieldsButKeepsTime() {
        assertEquals("— / — / 1234",
                text(HudFrame.sample(null, new Location(world, 200, 0, 200), 1234)));
    }

    @Test
    void timeUsesCurrentWorldTimeModuloOneDayRatherThanElapsedFullTime() {
        for (long value : new long[]{0, 23999, 24000, 48001, -1}) {
            assertEquals(Math.floorMod(value, 24000), HudFrame.sample(null, null, value).timeTick());
        }
    }

    private static String text(HudFrame frame) {
        return PlainTextComponentSerializer.plainText().serialize(frame.render());
    }
}
