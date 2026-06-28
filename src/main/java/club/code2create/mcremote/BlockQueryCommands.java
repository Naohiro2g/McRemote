package club.code2create.mcremote;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.logging.Logger;

public class BlockQueryCommands {
    private static final Logger logger = Logger.getLogger("McR_BlockQuery");
    private static final boolean debug = false;

    private final RemoteSession session;
    private final MiscCommands miscCommands;

    public BlockQueryCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
    }

    public void handleGetBlock(String[] args) {
        if (args.length != 3) {
            session.respondError(-32602, "malformed_ref", null);
            logger.warning("Invalid arguments for world.getBlock.");
            return;
        }
        try {
            World world = session.getOrigin().getWorld();
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            // 未ロード/未生成 chunk は getBlockAt が同期でロード・生成する（旧リリース挙動）。拒否はしない。
            Block block = world.getBlockAt(loc);
            // canonical-full block_state_ref（§7.1）を返す。
            session.respondResult(BlockRef.canonical(block.getBlockData()));
        } catch (NumberFormatException e) {
            session.respondError(-32602, "malformed_ref", null);
            logger.warning("Invalid coordinates for world.getBlock.");
        }
    }

    public void handleGetBlocks(String[] args) {
        if (args.length != 6) {
            sendAndLogWarning("Invalid arguments for getBlocks command.");
            return;
        }
        try {
            World world = session.getOrigin().getWorld();
            Location loc1 = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Location loc2 = miscCommands.parseRelativeBlockLocation(args[3], args[4], args[5]);

            int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
            int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
            int minY = Math.min(loc1.getBlockY(), loc2.getBlockY());
            int maxY = Math.max(loc1.getBlockY(), loc2.getBlockY());
            int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
            int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

            StringBuilder blocks = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        blocks.append(block.getType().name()).append(",");
                    }
                }
            }
            session.send(blocks.toString());
        } catch (NumberFormatException e) {
            sendAndLogWarning("Invalid coordinates for getBlocks command.");
        }
    }

    public void handleGetBlockWithData(String[] args) {
        if (args.length != 3) {
            sendAndLogWarning("Invalid arguments for getBlockWithData command.");
            return;
        }
        try {
            World world = session.getOrigin().getWorld();
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Block block = world.getBlockAt(loc);
            session.send(block.getType().name() + "," + block.getBlockData().getAsString());
        } catch (NumberFormatException e) {
            sendAndLogWarning("Invalid coordinates for getBlockWithData command.");
        }
    }

    private void sendAndLogWarning(String msg) {
        if (debug) {
            session.send("Error: " + msg);
        }
        logger.warning(msg);
    }
}
