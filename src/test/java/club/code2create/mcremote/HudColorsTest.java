package club.code2create.mcremote;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudColorsTest {
    @Test
    void existingConfigsWithoutColorsKeepTheSevenOriginalFixedColors() {
        List<String> warnings = new ArrayList<>();
        HudColors colors = HudColors.load(new YamlConfiguration(), warnings::add);
        assertEquals(0xd8d6d5, colors.absoluteTarget().value());
        assertEquals(0xa89b94, colors.absoluteLegs().value());
        assertEquals(0xf9c7e5, colors.relativeTarget().value());
        assertEquals(0xf15fb6, colors.relativeLegs().value());
        assertEquals(0x55c01b, colors.tick().value());
        assertEquals(0x55c01b, colors.time().value());
        assertEquals(0xaaaaaa, colors.separator().value());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void distributedHsbDefaultsMatchExistingColorsAndFallbacks() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            List<String> warnings = new ArrayList<>();
            assertEquals(HudColors.DEFAULT, HudColors.load(config, warnings::add));
            assertTrue(warnings.isEmpty());
        }
    }

    @Test
    void directionHueRunsClockwiseFromRedNorthWithoutQuantizingToEightDirections() {
        float[] yaw = {-180, -135, -90, -45, 0, 45, 90, 135};
        int[] rgb = {0xff0000, 0xffbf00, 0x80ff00, 0x00ff40, 0x00ffff, 0x0040ff, 0x8000ff, 0xff00bf};
        for (int i = 0; i < yaw.length; i++) {
            assertEquals(rgb[i], HudColors.DEFAULT.directionColor(yaw[i]).value());
            assertEquals(rgb[i], HudColors.DEFAULT.directionColor(yaw[i] + 720).value());
            assertEquals(rgb[i], HudColors.DEFAULT.directionColor(yaw[i] - 720).value());
        }
        assertEquals(0xff2b00, HudColors.DEFAULT.directionColor(-170).value());
        assertEquals(0xff0004, HudColors.DEFAULT.directionColor(179).value());
        assertEquals(0xff0400, HudColors.DEFAULT.directionColor(181).value());
    }

    @Test
    void degreesPercentagesFractionsAndThe360EndpointAreAccepted() {
        var config = new YamlConfiguration();
        config.set("hud.colors.absolute_target.h", 360);
        config.set("hud.colors.absolute_target.s", 100);
        config.set("hud.colors.absolute_target.b", 100);
        config.set("hud.colors.relative_target.h", 30.5);
        config.set("hud.colors.relative_target.s", 50);
        config.set("hud.colors.relative_target.b", 80);
        config.set("hud.colors.separator.b", 50);
        List<String> warnings = new ArrayList<>();
        HudColors colors = HudColors.load(config, warnings::add);
        assertEquals(0xff0000, colors.absoluteTarget().value());
        assertEquals(0xcc9a66, colors.relativeTarget().value());
        assertEquals(0x808080, colors.separator().value());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void invalidChannelRestoresOnlyItsWholeColorAndNamesTheSettingInTheWarning() {
        for (String channel : List.of("h", "s", "b")) {
            double max = channel.equals("h") ? 360 : 100;
            for (Object invalid : List.of("red", true, -0.1, max + 0.1,
                    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                var config = new YamlConfiguration();
                config.set("hud.colors.absolute_target.h", 0);
                config.set("hud.colors.absolute_target.s", 100);
                config.set("hud.colors.absolute_target.b", 100);
                config.set("hud.colors.absolute_target." + channel, invalid);
                config.set("hud.colors.separator.b", 50);
                List<String> warnings = new ArrayList<>();
                HudColors colors = HudColors.load(config, warnings::add);
                assertEquals(HudColors.DEFAULT.absoluteTarget(), colors.absoluteTarget());
                assertEquals(0x808080, colors.separator().value());
                assertEquals(1, warnings.size());
                assertTrue(warnings.getFirst().contains("hud.colors.absolute_target." + channel));
            }
        }
    }

    @Test
    void malformedMappingsWarnAndDirectionSettingsValidateWithoutChangingOtherColors() {
        for (String path : List.of("hud.colors", "hud.colors.time", "hud.colors.direction")) {
            var config = new YamlConfiguration();
            config.set(path, "red");
            List<String> warnings = new ArrayList<>();
            assertEquals(HudColors.DEFAULT, HudColors.load(config, warnings::add));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains(path));
        }
        var config = new YamlConfiguration();
        config.set("hud.colors.direction.s", 20);
        config.set("hud.colors.direction.b", 101);
        List<String> warnings = new ArrayList<>();
        assertEquals(HudColors.DEFAULT, HudColors.load(config, warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("hud.colors.direction.b"));
    }

    @Test
    void directionSaturationAndBrightnessCanProduceMutedOrAchromaticText() {
        var config = new YamlConfiguration();
        config.set("hud.colors.direction.s", 25);
        config.set("hud.colors.direction.b", 80);
        HudColors muted = HudColors.load(config, warning -> fail(warning));
        assertEquals(0xcc9999, muted.directionColor(180).value());
        config.set("hud.colors.direction.s", 0);
        HudColors gray = HudColors.load(config, warning -> fail(warning));
        for (float yaw : new float[]{0, 60, 90, 135, 180, 270}) {
            assertEquals(0xcccccc, gray.directionColor(yaw).value());
        }
    }
}
