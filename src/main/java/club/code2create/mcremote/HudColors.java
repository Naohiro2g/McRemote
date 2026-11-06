package club.code2create.mcremote;

import java.util.function.Consumer;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable startup palette. H is in degrees; S and B are percentages. */
record HudColors(TextColor absoluteTarget, TextColor absoluteLegs,
                 TextColor relativeTarget, TextColor relativeLegs,
                 TextColor tick, TextColor time, TextColor separator,
                 double directionSaturation, double directionBrightness,
                 double directionMarkSaturation, double directionMarkBrightness) {
    private static final Hsb ABSOLUTE_TARGET = new Hsb(40, 90, 90);
    private static final Hsb ABSOLUTE_LEGS = new Hsb(40, 40, 90);
    private static final Hsb RELATIVE_TARGET = new Hsb(320, 90, 90);
    private static final Hsb RELATIVE_LEGS = new Hsb(320, 40, 90);
    private static final Hsb DIRECTION_MARK = new Hsb(0, 75, 100);
    private static final Hsb DIRECTION = new Hsb(0, 40, 90);
    private static final Hsb TICK = new Hsb(170, 80, 85);
    private static final Hsb TIME = new Hsb(120, 80, 85);
    private static final Hsb SEPARATOR = new Hsb(0, 0, 60);

    static final HudColors DEFAULT = new HudColors(ABSOLUTE_TARGET.color(), ABSOLUTE_LEGS.color(),
            RELATIVE_TARGET.color(), RELATIVE_LEGS.color(), TICK.color(), TIME.color(), SEPARATOR.color(),
            DIRECTION.s(), DIRECTION.b(), DIRECTION_MARK.s(), DIRECTION_MARK.b());

    static HudColors load(ConfigurationSection config, Consumer<String> warning) {
        ConfigurationSection colors = config.getConfigurationSection("hud.colors");
        if (colors == null) {
            if (config.contains("hud.colors")) warn(warning, "hud.colors", "HSB color mappings");
            return DEFAULT;
        }
        Hsb direction = read(colors, "direction", DIRECTION, true, warning);
        Hsb directionMark = read(colors, "direction_mark", DIRECTION_MARK, true, warning);
        return new HudColors(
                read(colors, "absolute_target", ABSOLUTE_TARGET, false, warning).color(),
                read(colors, "absolute_legs", ABSOLUTE_LEGS, false, warning).color(),
                read(colors, "relative_target", RELATIVE_TARGET, false, warning).color(),
                read(colors, "relative_legs", RELATIVE_LEGS, false, warning).color(),
                read(colors, "tick", TICK, false, warning).color(),
                read(colors, "time", TIME, false, warning).color(),
                read(colors, "separator", SEPARATOR, false, warning).color(), direction.s(), direction.b(),
                directionMark.s(), directionMark.b());
    }

    TextColor directionColor(float yaw) {
        return compassColor(yaw, directionSaturation, directionBrightness);
    }

    TextColor directionMarkColor(float yaw) {
        return compassColor(yaw, directionMarkSaturation, directionMarkBrightness);
    }

    private static TextColor compassColor(float yaw, double saturation, double brightness) {
        // Minecraft yaw: south=0, west=90. Compass hue: north=0, east=90.
        double hue = (yaw % 360.0 + 540.0) % 360.0;
        return new Hsb(hue, saturation, brightness).color();
    }

    private static Hsb read(ConfigurationSection colors, String key, Hsb fallback,
                            boolean direction, Consumer<String> warning) {
        if (!colors.contains(key)) return fallback;
        String path = "hud.colors." + key;
        ConfigurationSection value = colors.getConfigurationSection(key);
        if (value == null) {
            warn(warning, path, direction ? "an S/B mapping" : "an H/S/B mapping");
            return fallback;
        }
        try {
            return new Hsb(direction ? 0 : number(value, "h", fallback.h(), 360),
                    number(value, "s", fallback.s(), 100), number(value, "b", fallback.b(), 100));
        } catch (IllegalArgumentException invalid) {
            warn(warning, path + "." + invalid.getMessage(), "a finite number in "
                    + ("h".equals(invalid.getMessage()) ? "0..360" : "0..100"));
            return fallback;
        }
    }

    private static double number(ConfigurationSection config, String key, double fallback, double max) {
        Object value = config.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key);
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < 0 || result > max) throw new IllegalArgumentException(key);
        return result;
    }

    private static void warn(Consumer<String> warning, String path, String expected) {
        warning.accept("Invalid " + path + "; expected " + expected + ". Using the default HUD color.");
    }

    private record Hsb(double h, double s, double b) {
        TextColor color() {
            double sector = (h % 360) / 60;
            double brightness = b / 100;
            double chroma = brightness * s / 100;
            double secondary = chroma * (1 - Math.abs(sector % 2 - 1));
            double minimum = brightness - chroma;
            return switch ((int) sector) {
                case 0 -> rgb(chroma, secondary, 0, minimum);
                case 1 -> rgb(secondary, chroma, 0, minimum);
                case 2 -> rgb(0, chroma, secondary, minimum);
                case 3 -> rgb(0, secondary, chroma, minimum);
                case 4 -> rgb(secondary, 0, chroma, minimum);
                default -> rgb(chroma, 0, secondary, minimum);
            };
        }

        private static TextColor rgb(double r, double g, double b, double minimum) {
            // Round to the nearest 8-bit channel to preserve the existing palette.
            return TextColor.color(channel(r + minimum), channel(g + minimum), channel(b + minimum));
        }

        private static int channel(double value) {
            // Keep exact half-way values from rounding down due to binary arithmetic noise.
            return (int) Math.round(value * 255 + 1e-9);
        }
    }
}
