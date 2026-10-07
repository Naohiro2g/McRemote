package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.bukkit.Location;
import org.bukkit.Bukkit;
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
    private BlockEditCursor pending;

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
            if (!session.isWithinBuildBlocks(1)) {
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
        if (pending != null) { resumeCuboid(); return; }
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
            if (!session.isWithinBuildBlocks(volume)) {
                session.respondError(-32000, "build_denied", null);
                return;
            }
            if (!session.admitBulkRequest(volume)) {
                return;
            }
            pending = new BlockEditCursor(world, loc1, loc2, data, (int) volume);
        } catch (BlockCodec.ValidationException e) {
            session.respondError(-32602, e.reason, e.data);
            logger.warning("Invalid BlockSpec for world.setBlocks: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", pathData("params"));
            logger.warning("Invalid parameters for world.setBlocks: " + e.getMessage());
        }
        resumeCuboid();
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

    void cancelPending() {
        pending = null;
    }

    private void resumeCuboid() {
        BlockEditCursor cursor = pending;
        if (cursor == null) return;
        if (session.pendingRemoval) { cancelPending(); return; }
        int units = session.reserveBulkWork(cursor.remaining);
        if (units == 0) {
            if (cursor.started) throw CommandDeferredException.INSTANCE;
            if (!session.rejectTemporaryBackpressure()) { cancelPending(); return; }
        }
        try {
            if (Bukkit.getWorld(cursor.world.getUID()) != cursor.world) {
                throw new IllegalStateException("setBlocks world is no longer available");
            }
            for (int i = 0; i < units; i++) {
                if (session.pendingRemoval) { cancelPending(); return; }
                cursor.world.getBlockAt(cursor.x, cursor.y, cursor.z).setBlockData(cursor.data, false);
                cursor.advance();
            }
        } catch (RuntimeException e) {
            cancelPending();
            session.respondError(-32603, "internal_error", null);
            logger.warning("Paper operation failed during world.setBlocks: " + e.getClass().getSimpleName());
            return;
        }
        if (cursor.remaining != 0) throw CommandDeferredException.INSTANCE;
        cancelPending();
        session.respondResult(null);
    }

    private static Map<String, Object> pathData(String path) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return data;
    }
}
