package club.code2create.mcremote;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

/** Main-thread sampling of the player's feet or the actual targeted block. */
final class HudSampler {
    static final double TARGET_DISTANCE = 16;

    enum Point { LEGS, TARGET }

    private HudSampler() {}

    static HudFrame sample(Player player, Location origin, Point point) {
        Location viewer = player.getLocation();
        Location location;
        if (point == Point.LEGS) {
            location = viewer;
        } else {
            RayTraceResult hit = player.rayTraceBlocks(TARGET_DISTANCE, FluidCollisionMode.NEVER);
            location = hit == null || hit.getHitBlock() == null ? null : hit.getHitBlock().getLocation();
        }
        return HudFrame.sample(location, origin, player.getWorld().getTime(), point, viewer, player.locale());
    }
}
