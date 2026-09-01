package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** protocol 22 structured BlockSpec handlers for world.setBlock/setBlocks. */
public class BlockEditCommands {
    private static final Logger logger = Logger.getLogger("McR_BlockEdit");
    private static final int WORLD_LIMIT = 1_000_000;
    private static final int SKY_LIMIT = 1_000;

    private final RemoteSession session;
    private final MiscCommands miscCommands;
    private final BlockCodec blockCodec;

    public BlockEditCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
        this.blockCodec = new BlockCodec(session.getPlugin().getCatalogService());
    }

    public void handleSetBlock(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 4);
            int x = coordinate(args, 0);
            int y = coordinate(args, 1);
            int z = coordinate(args, 2);
            BlockData data = blockCodec.decode(args.get(3), "params[3]");
            World world = session.getOrigin().getWorld();
            Location loc = miscCommands.parseRelativeBlockLocation(x, y, z);
            if (!session.hasConstructionPermission()) {
                session.respondError(-32000, "permission_denied", null);
                return;
            }
            if (!checkRange(loc)) {
                session.respondError(-32000, "build_denied", null);
                return;
            }
            if (!session.admitSetterWork(1)) {
                return;
            }
            Block block = world.getBlockAt(loc);
            block.setBlockData(data, false);
            session.respondResult(null);
        } catch (BlockCodec.ValidationException e) {
            session.respondError(-32602, e.reason, e.data);
            logger.warning("Invalid BlockSpec for world.setBlock: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", pathData("params"));
            logger.warning("Invalid parameters for world.setBlock: " + e.getMessage());
        }
    }

    public void handleSetBlocks(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 7);
            int x1 = coordinate(args, 0);
            int y1 = coordinate(args, 1);
            int z1 = coordinate(args, 2);
            int x2 = coordinate(args, 3);
            int y2 = coordinate(args, 4);
            int z2 = coordinate(args, 5);
            BlockData data = blockCodec.decode(args.get(6), "params[6]");
            World world = session.getOrigin().getWorld();
            Location loc1 = miscCommands.parseRelativeBlockLocation(x1, y1, z1);
            Location loc2 = miscCommands.parseRelativeBlockLocation(x2, y2, z2);
            if (!session.hasConstructionPermission()) {
                session.respondError(-32000, "permission_denied", null);
                return;
            }
            if (!checkRange(loc1) || !checkRange(loc2)) {
                session.respondError(-32000, "build_denied", null);
                return;
            }
            long volume = BlockEditVolume.between(x1, y1, z1, x2, y2, z2);
            if (!session.admitSetterWork(volume)) {
                return;
            }
            setCuboid(world, loc1, loc2, data);
            session.respondResult(null);
        } catch (BlockCodec.ValidationException e) {
            session.respondError(-32602, e.reason, e.data);
            logger.warning("Invalid BlockSpec for world.setBlocks: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", pathData("params"));
            logger.warning("Invalid parameters for world.setBlocks: " + e.getMessage());
        }
    }

    private int coordinate(JsonArray args, int index) {
        int coordinate = WireParams.integer(args, index);
        int limit = index % 3 == 1 ? SKY_LIMIT : WORLD_LIMIT;
        if (coordinate < -limit || coordinate > limit) {
            throw new IllegalArgumentException("coordinate outside supported range");
        }
        return coordinate;
    }

    private boolean checkRange(Location targetLoc) {
        return session.isWithinBuildRange(targetLoc);
    }

    private void setCuboid(World world, Location loc1, Location loc2, BlockData data) {
        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int minY = Math.min(loc1.getBlockY(), loc2.getBlockY());
        int maxY = Math.max(loc1.getBlockY(), loc2.getBlockY());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = maxY; y >= minY; y--) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.getBlockAt(x, y, z).setBlockData(data, false);
                }
            }
        }
    }

    private static Map<String, Object> pathData(String path) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return data;
    }
}
