package club.code2create.mcremote;

import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

/** Request-time world limits, independent of construction permission and work budgets. */
final class WorldBounds {
    private WorldBounds() {}

    static Location absolute(Location origin, int x, int y, int z) {
        return new Location(origin.getWorld(), (double) origin.getBlockX() + x,
                (double) origin.getBlockY() + y, (double) origin.getBlockZ() + z);
    }

    static boolean permits(RemoteSession session, World world, Location... points) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        WorldBorder border = world.getWorldBorder();
        for (Location point : points) {
            if (point.getY() < minY || point.getY() > maxY || !border.isInside(point)) {
                Location center = border.getCenter();
                Map<String, Object> bounds = Map.of("y_min", minY, "y_max", maxY,
                        "world_border", Map.of("center", Map.of("x", center.getX(), "z", center.getZ()),
                                "size", border.getSize()));
                session.respondError(-32000, "out_of_bounds", Map.of("bounds", bounds,
                        "violating", Map.of("x", (long) point.getX(), "y", (long) point.getY(), "z", (long) point.getZ())));
                return false;
            }
        }
        return true;
    }
}
