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
    void missingColorsUseTheReleasePalette() {
        List<String> warnings = new ArrayList<>();
        HudColors colors = HudColors.load(new YamlConfiguration(), warnings::add);
        assertEquals(0xe6a117, colors.absoluteTarget().value());
        assertEquals(0xe6c78a, colors.absoluteLegs().value());
        assertEquals(0xe617a1, colors.relativeTarget().value());
        assertEquals(0xe68ac7, colors.relativeLegs().value());
        assertEquals(0x2bd9bc, colors.tick().value());
        assertEquals(0x2bd92b, colors.time().value());
        assertEquals(0x999999, colors.separator().value());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void distributedHsbDefaultsMatchTheReleasePaletteAndFallbacks() throws Exception {
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
        var config = new YamlConfiguration();
        config.set("hud.colors.direction.s", 100);
        config.set("hud.colors.direction.b", 100);
        config.set("hud.colors.direction_mark.s", 100);
        config.set("hud.colors.direction_mark.b", 100);
        HudColors colors = HudColors.load(config, warning -> fail(warning));
        float[] yaw = {-180, -135, -90, -45, 0, 45, 90, 135};
        int[] rgb = {0xff0000, 0xffbf00, 0x80ff00, 0x00ff40, 0x00ffff, 0x0040ff, 0x8000ff, 0xff00bf};
        for (int i = 0; i < yaw.length; i++) {
            assertEquals(rgb[i], colors.directionColor(yaw[i]).value());
            assertEquals(rgb[i], colors.directionMarkColor(yaw[i]).value());
            assertEquals(rgb[i], colors.directionColor(yaw[i] + 720).value());
            assertEquals(rgb[i], colors.directionColor(yaw[i] - 720).value());
        }
        assertEquals(0xff2b00, colors.directionColor(-170).value());
        assertEquals(0xff0004, colors.directionColor(179).value());
        assertEquals(0xff0400, colors.directionColor(181).value());
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
        for (String path : List.of("hud.colors", "hud.colors.time", "hud.colors.direction", "hud.colors.direction_mark")) {
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
    void invalidMarkerSettingsFallBackWithoutResettingTheTextSettings() {
        var config = new YamlConfiguration();
        config.set("hud.colors.direction.s", 20);
        config.set("hud.colors.direction.b", 100);
        config.set("hud.colors.direction_mark.s", 50);
        config.set("hud.colors.direction_mark.b", 101);
        List<String> warnings = new ArrayList<>();
        HudColors colors = HudColors.load(config, warnings::add);
        assertEquals(0xffcccc, colors.directionColor(180).value());
        assertEquals(0xff4040, colors.directionMarkColor(180).value());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("hud.colors.direction_mark.b"));
    }

    @Test
    void directionSaturationAndBrightnessCanProduceMutedOrAchromaticText() {
        var config = new YamlConfiguration();
        config.set("hud.colors.direction.s", 25);
        config.set("hud.colors.direction.b", 80);
        HudColors muted = HudColors.load(config, warning -> fail(warning));
        assertEquals(0xcc9999, muted.directionColor(180).value());
        assertEquals(0xff4040, muted.directionMarkColor(180).value());
        config.set("hud.colors.direction.s", 0);
        HudColors gray = HudColors.load(config, warning -> fail(warning));
        for (float yaw : new float[]{0, 60, 90, 135, 180, 270}) {
            assertEquals(0xcccccc, gray.directionColor(yaw).value());
        }
        config.set("hud.colors.direction_mark.s", 50);
        config.set("hud.colors.direction_mark.b", 40);
        HudColors separate = HudColors.load(config, warning -> fail(warning));
        assertEquals(0xcccccc, separate.directionColor(180).value());
        assertEquals(0x663333, separate.directionMarkColor(180).value());
        assertEquals(0x336666, separate.directionMarkColor(0).value());
    }
}
