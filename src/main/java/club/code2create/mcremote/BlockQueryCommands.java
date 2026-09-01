package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class BlockQueryCommands {
    private static final Logger logger = Logger.getLogger("McR_BlockQuery");

    private final RemoteSession session;
    private final MiscCommands miscCommands;

    public BlockQueryCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
    }

    public void handleGetBlock(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 3);
            int x = WireParams.integer(args, 0);
            int y = WireParams.integer(args, 1);
            int z = WireParams.integer(args, 2);
            World world = session.getOrigin().getWorld();
            Location loc = miscCommands.parseRelativeBlockLocation(x, y, z);
            if (!preflight(loc)) {
                return;
            }
            // 未ロード/未生成 chunk は getBlockAt が同期でロード・生成する（旧リリース挙動）。拒否はしない。
            Block block = world.getBlockAt(loc);
            session.respondResult(BlockCodec.encode(block.getBlockData()));
        } catch (IllegalArgumentException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", "params");
            session.respondError(-32602, "invalid_params", data);
            logger.warning("Invalid parameters for world.getBlock: " + e.getMessage());
        }
    }

    public void handleGetBlocks(JsonElement params) {
        try {
            // The axis/work admission is deliberately completed before build-origin or world access.
            BlockQueryRegion relative = BlockQueryRegion.parse(params);
            Location origin = session.getOrigin();
            BlockQueryRegion absolute = relative.translate(
                    origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
            Location min = new Location(origin.getWorld(), absolute.minX(), origin.getY(), absolute.minZ());
            Location max = new Location(origin.getWorld(), absolute.maxX(), origin.getY(), absolute.maxZ());
            if (!preflight(min) || !preflight(max)) {
                return;
            }
            World world = origin.getWorld();
            List<Map<String, Object>> values = new ArrayList<>(absolute.size());
            for (BlockQueryRegion.Position position : absolute.positions()) {
                values.add(BlockCodec.encode(world.getBlockAt(
                        position.x(), position.y(), position.z()).getBlockData()));
            }
            session.respondResult(List.copyOf(values));
        } catch (BlockQueryRegion.WorkLimitExceededException e) {
            session.respondError(-32000, "work_limit_exceeded", null);
            logger.warning("world.getBlocks region exceeds 10 blocks on an axis.");
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", pathData("params"));
            logger.warning("Invalid parameters for world.getBlocks: " + e.getMessage());
        }
    }

    private boolean preflight(Location target) {
        if (!session.hasConstructionPermission()) {
            session.respondError(-32000, "permission_denied", null);
            return false;
        }
        if (!session.isWithinBuildRange(target)) {
            session.respondError(-32000, "build_denied", null);
            return false;
        }
        return true;
    }

    private static Map<String, Object> pathData(String path) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return data;
    }

}
