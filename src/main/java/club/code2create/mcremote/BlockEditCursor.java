package club.code2create.mcremote;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Constant-space cuboid progress; retains the historical x/up-to-down-y/z order. */
final class BlockEditCursor {
    final World world;
    final BlockData data;
    private final int minY, maxY, minZ, maxZ, maxX;
    int x, y, z, remaining;
    boolean started;

    BlockEditCursor(World world, Location first, Location last, BlockData data, int volume) {
        this.world = world;
        this.data = data;
        x = Math.min(first.getBlockX(), last.getBlockX());
        maxX = Math.max(first.getBlockX(), last.getBlockX());
        minY = Math.min(first.getBlockY(), last.getBlockY());
        maxY = Math.max(first.getBlockY(), last.getBlockY());
        minZ = Math.min(first.getBlockZ(), last.getBlockZ());
        maxZ = Math.max(first.getBlockZ(), last.getBlockZ());
        y = maxY;
        z = minZ;
        remaining = volume;
    }

    void advance() {
        started = true;
        remaining--;
        if (remaining == 0) return;
        if (z < maxZ) { z++; return; }
        z = minZ;
        if (y > minY) { y--; return; }
        y = maxY;
        if (x >= maxX) throw new IllegalStateException("cuboid volume disagrees with cursor");
        x++;
    }
}
