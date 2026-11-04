package club.code2create.mcremote;

import java.util.Locale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudDirectionTest {
    @Test
    void eightCompassDirectionsFollowMinecraftYawAndClientLanguage() {
        String[] english = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        String[] japanese = {"南", "南西", "西", "北西", "北", "北東", "東", "南東"};
        for (int i = 0; i < 8; i++) {
            assertEquals(english[i], HudDirection.fromYaw(i * 45).label(Locale.ENGLISH));
            assertEquals(japanese[i], HudDirection.fromYaw(i * 45).label(Locale.JAPAN));
        }
        assertEquals("N", HudDirection.fromYaw(180).label(Locale.FRENCH));
        assertEquals("北", HudDirection.fromYaw(180).label(Locale.JAPANESE));
    }

    @Test
    void sectorBoundariesAndNegativeOrWrappedAnglesAreConsistent() {
        assertEquals(HudDirection.SOUTH, HudDirection.fromYaw(22.49f));
        assertEquals(HudDirection.SOUTH_WEST, HudDirection.fromYaw(22.5f));
        assertEquals(HudDirection.SOUTH_WEST, HudDirection.fromYaw(67.49f));
        assertEquals(HudDirection.WEST, HudDirection.fromYaw(67.5f));
        assertEquals(HudDirection.SOUTH, HudDirection.fromYaw(-22.5f));
        assertEquals(HudDirection.SOUTH_EAST, HudDirection.fromYaw(-22.51f));
        assertEquals(HudDirection.EAST, HudDirection.fromYaw(-90));
        assertEquals(HudDirection.SOUTH, HudDirection.fromYaw(720));
        assertEquals(HudDirection.NORTH_EAST, HudDirection.fromYaw(-495));
    }
}
