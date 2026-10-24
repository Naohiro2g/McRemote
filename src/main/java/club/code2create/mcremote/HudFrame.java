package club.code2create.mcremote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;

/** One sampled coordinate/time view. Sampling never changes a world, player or build origin. */
record HudFrame(HudFrame.Coordinates absolute, HudFrame.Coordinates relative, long timeTick, HudSampler.Point point) {
    private static final TextColor ABSOLUTE_BODY = TextColor.color(0xa89b94);
    private static final TextColor ABSOLUTE_TARGET = TextColor.color(0xd8d6d5);
    private static final TextColor RELATIVE_BODY = TextColor.color(0xf15fb6);
    private static final TextColor RELATIVE_TARGET = TextColor.color(0xf9c7e5);
    private static final TextColor TIME = TextColor.color(0x55c01b);

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
        return sample(point, origin, worldTime, HudSampler.Point.BODY);
    }

    static HudFrame sample(Location point, Location origin, long worldTime, HudSampler.Point mode) {
        Coordinates absolute = point == null ? null : Coordinates.at(point);
        Coordinates relative = absolute != null && origin != null && point.getWorld() != null
                && point.getWorld().equals(origin.getWorld()) ? absolute.relativeTo(origin) : null;
        return new HudFrame(absolute, relative, Math.floorMod(worldTime, 24_000L), mode);
    }

    Component render() {
        boolean target = point == HudSampler.Point.TARGET;
        return Component.text(absolute == null ? "—" : absolute.text(), target ? ABSOLUTE_TARGET : ABSOLUTE_BODY)
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(relative == null ? "—" : relative.text(), target ? RELATIVE_TARGET : RELATIVE_BODY))
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(Long.toString(timeTick), TIME));
    }
}
