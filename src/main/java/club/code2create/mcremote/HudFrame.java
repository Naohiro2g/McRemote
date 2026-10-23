package club.code2create.mcremote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;

/** One sampled coordinate/time view. Sampling never changes a world, player or build origin. */
record HudFrame(HudFrame.Coordinates absolute, HudFrame.Coordinates relative, long timeTick) {
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

    static HudFrame sample(Location point, Location origin, long worldTime) {
        Coordinates absolute = point == null ? null : Coordinates.at(point);
        Coordinates relative = absolute != null && origin != null && point.getWorld() != null
                && point.getWorld().equals(origin.getWorld()) ? absolute.relativeTo(origin) : null;
        return new HudFrame(absolute, relative, Math.floorMod(worldTime, 24_000L));
    }

    Component render() {
        return Component.text(absolute == null ? "—" : absolute.text(), NamedTextColor.WHITE)
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(relative == null ? "—" : relative.text(), NamedTextColor.AQUA))
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(Long.toString(timeTick), NamedTextColor.YELLOW));
    }
}
