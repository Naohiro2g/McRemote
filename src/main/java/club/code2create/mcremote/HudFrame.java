package club.code2create.mcremote;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;

/** One sampled coordinate/time view. Sampling never changes a world, player or build origin. */
record HudFrame(HudFrame.Coordinates absolute, HudFrame.Coordinates relative, long timeTick, HudSampler.Point point,
                boolean relativeOriginAvailable, HudDirection direction, Locale locale, float yaw) {

    record Coordinates(long x, long y, long z) {
        static Coordinates at(Location point) {
            return new Coordinates(point.getBlockX(), point.getBlockY(), point.getBlockZ());
        }

        Coordinates relativeTo(Location origin) {
            return new Coordinates(x - origin.getBlockX(), y - origin.getBlockY(), z - origin.getBlockZ());
        }

        String text() {
            return x + " " + y + " " + z;
        }
    }

    static HudFrame sample(Location point, Location origin, long worldTime, HudSampler.Point mode,
                           Location viewer, Locale locale) {
        Coordinates absolute = point == null ? null : Coordinates.at(point);
        boolean originAvailable = origin != null && viewer.getWorld() != null
                && viewer.getWorld().equals(origin.getWorld())
                && (point == null || viewer.getWorld().equals(point.getWorld()));
        Coordinates relative = absolute != null && originAvailable ? absolute.relativeTo(origin) : null;
        long tick = Math.floorMod(worldTime, 24_000L) / 10 * 10;
        return new HudFrame(absolute, relative, tick, mode, originAvailable,
                HudDirection.fromYaw(viewer.getYaw()), locale, viewer.getYaw());
    }

    String clockText() {
        long minutes = (timeTick + 6_000L) % 24_000L * 1_440L / 24_000L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    Component render(HudColors colors) {
        boolean target = point == HudSampler.Point.TARGET;
        String relativeText = !relativeOriginAvailable ? "[ - - - ]" : relative == null ? "[-]" : relative.text();
        return Component.text(absolute == null ? "[-]" : absolute.text(), target ? colors.absoluteTarget() : colors.absoluteLegs())
                .append(Component.text(" / ", colors.separator()))
                .append(Component.text(relativeText, target ? colors.relativeTarget() : colors.relativeLegs()))
                .append(Component.text(" / ", colors.separator()))
                .append(Component.text(direction.label(locale), colors.directionColor(yaw)))
                .append(Component.text(" / ", colors.separator()))
                .append(Component.text(Long.toString(timeTick), colors.tick()))
                .append(Component.text(" / ", colors.separator()))
                .append(Component.text(clockText(), colors.time()));
    }
}
