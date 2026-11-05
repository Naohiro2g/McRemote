package club.code2create.mcremote;

import java.util.function.Consumer;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable startup palette. H is in degrees; S and B are percentages. */
record HudColors(TextColor absoluteTarget, TextColor absoluteLegs,
                 TextColor relativeTarget, TextColor relativeLegs,
                 TextColor tick, TextColor time, TextColor separator,
                 double directionSaturation, double directionBrightness) {
    private static final Hsb ABSOLUTE_TARGET = new Hsb(20, 1.388889, 84.705882);
    private static final Hsb ABSOLUTE_LEGS = new Hsb(21, 11.904762, 65.882353);
    private static final Hsb RELATIVE_TARGET = new Hsb(324, 20.080321, 97.647059);
    private static final Hsb RELATIVE_LEGS = new Hsb(324.246575, 60.580913, 94.509804);
    private static final Hsb TIME = new Hsb(98.909091, 85.9375, 75.294118);
    private static final Hsb SEPARATOR = new Hsb(0, 0, 66.666667);
    private static final Hsb DIRECTION = new Hsb(0, 100, 100);

    static final HudColors DEFAULT = new HudColors(ABSOLUTE_TARGET.color(), ABSOLUTE_LEGS.color(),
            RELATIVE_TARGET.color(), RELATIVE_LEGS.color(), TIME.color(), TIME.color(), SEPARATOR.color(),
            DIRECTION.s(), DIRECTION.b());

    static HudColors load(ConfigurationSection config, Consumer<String> warning) {
        ConfigurationSection colors = config.getConfigurationSection("hud.colors");
        if (colors == null) {
            if (config.contains("hud.colors")) warn(warning, "hud.colors", "HSB color mappings");
            return DEFAULT;
        }
        Hsb direction = read(colors, "direction", DIRECTION, true, warning);
        return new HudColors(
                read(colors, "absolute_target", ABSOLUTE_TARGET, false, warning).color(),
                read(colors, "absolute_legs", ABSOLUTE_LEGS, false, warning).color(),
                read(colors, "relative_target", RELATIVE_TARGET, false, warning).color(),
                read(colors, "relative_legs", RELATIVE_LEGS, false, warning).color(),
                read(colors, "tick", TIME, false, warning).color(),
                read(colors, "time", TIME, false, warning).color(),
                read(colors, "separator", SEPARATOR, false, warning).color(), direction.s(), direction.b());
    }

    TextColor directionColor(float yaw) {
        // Minecraft yaw: south=0, west=90. Compass hue: north=0, east=90.
        double hue = (yaw % 360.0 + 540.0) % 360.0;
        return new Hsb(hue, directionSaturation, directionBrightness).color();
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
