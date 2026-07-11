package club.code2create.mcremote;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * ブロック設置（wire-format-design §7.1/§7.3）。
 * 4番目（setBlock）/7番目（setBlocks）の引数は block_state_ref 文字列で、
 * BlockRef が tolerate パース（無印→minecraft:・部分 state）する。旧 facing int 引数は廃止
 * （向きは state、例 [facing=north] が運ぶ）。
 *
 * 既定は send-only（notification）だが、id 付き要求には同期応答する（§7.3, DECISIONS 2026-06-27-04）。
 * respondResult/respondError は notification では no-op なので、高速建築の fire-and-forget は保たれる。
 */
public class BlockEditCommands {
    private static final Logger logger = Logger.getLogger("McR_BlockEdit");

    private final RemoteSession session;
    private final MiscCommands miscCommands;

    public BlockEditCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
    }

    public void handleSetBlock(String[] args) {
        if (args.length < 4) {
            session.respondError(-32602, "malformed_ref", null);
            logger.warning("Invalid arguments for world.setBlock.");
            return;
        }
        if (isInvalidCoordinate(args[0], args[1], args[2])) {
            session.respondError(-32602, "malformed_ref", refData(args[0] + "," + args[1] + "," + args[2]));
            logger.warning("Coordinates out of range for world.setBlock.");
            return;
        }

        BlockData data;
        try {
            data = BlockRef.parse(args[3]);
        } catch (BlockRef.BlockRefException e) {
            session.respondError(e.code, e.reason, refData(args[3]));
            logger.warning("Bad block_state_ref for world.setBlock: " + args[3] + " (" + e.reason + ")");
            return;
        }

        try {
            World world = session.getOrigin().getWorld();
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            // 未ロード/未生成 chunk は getBlockAt/setBlockData が同期でロード・生成する（旧リリース挙動）。
            // isChunkLoaded での拒否はしない＝生成済み/未生成・プレイヤー在席に依らず設置できる。
            if (!checkRange(loc)) {
                session.respondError(-32000, "build_denied", refData(args[3]));
                return;
            }
            Block block = world.getBlockAt(loc);
            block.setBlockData(data, false);
            // id 付き要求には設置後の canonical を返す（§7.1）。notification は no-op。
            session.respondResult(BlockRef.canonical(block.getBlockData()));
        } catch (NumberFormatException e) {
            session.respondError(-32602, "malformed_ref", refData(args[3]));
            logger.warning("Invalid coordinates for world.setBlock.");
        }
    }

    public void handleSetBlocks(String[] args) {
        if (args.length < 7) {
            session.respondError(-32602, "malformed_ref", null);
            logger.warning("Invalid arguments for world.setBlocks.");
            return;
        }
        if (isInvalidCoordinate(args[0], args[1], args[2]) || isInvalidCoordinate(args[3], args[4], args[5])) {
            session.respondError(-32602, "malformed_ref", null);
            logger.warning("Coordinates out of range for world.setBlocks.");
            return;
        }

        BlockData data;
        try {
            data = BlockRef.parse(args[6]);
        } catch (BlockRef.BlockRefException e) {
            session.respondError(e.code, e.reason, refData(args[6]));
            logger.warning("Bad block_state_ref for world.setBlocks: " + args[6] + " (" + e.reason + ")");
            return;
        }

        try {
            World world = session.getOrigin().getWorld();
            Location loc1 = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Location loc2 = miscCommands.parseRelativeBlockLocation(args[3], args[4], args[5]);
            if (!checkRange(loc1) || !checkRange(loc2)) {
                session.respondError(-32000, "build_denied", refData(args[6]));
                return;
            }
            setCuboid(world, loc1, loc2, data);
            // 一様充填なので canonical を1つ返す（id 付き要求のみ）。
            session.respondResult(BlockRef.canonical(data));
        } catch (NumberFormatException e) {
            session.respondError(-32602, "malformed_ref", refData(args[6]));
            logger.warning("Invalid coordinates for world.setBlocks.");
        }
    }

    private boolean checkRange(Location targetLoc) {
        Location origin = session.getOrigin();
        if (origin == null) {
            return false;
        }
        int allowedRange = resolveBuildRange();
        double dx = Math.abs(targetLoc.getX() - origin.getX());
        double dz = Math.abs(targetLoc.getZ() - origin.getZ());
        return dx <= allowedRange && dz <= allowedRange;
    }

    /**
     * 建築許容範囲を返す。プレイヤーが紐付いていれば PermissionManager から、
     * いなければ（build.setOrigin 経路など）config の default_build_range を使う。
     */
    private int resolveBuildRange() {
        PlayerCommands playerCommands = session.getPlayerCommands();
        org.bukkit.OfflinePlayer player = playerCommands != null ? playerCommands.getAttachedPlayer() : null;
        if (player != null) {
            return McRemote.instance.getPermissionManager().getPlayerRange(player);
        }
        return McRemote.getInstance().getDefaultBuildRange();
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

    private boolean isInvalidCoordinate(String xStr, String yStr, String zStr) {
        final int worldLimit = 1000000;
        final int skyLimit = 1000;
        try {
            int x = Integer.parseInt(xStr);
            int y = Integer.parseInt(yStr);
            int z = Integer.parseInt(zStr);
            return x < -worldLimit || x > worldLimit
                    || y < -skyLimit || y > skyLimit
                    || z < -worldLimit || z > worldLimit;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** §7.3 data.ref（問題の入力エコー）を1要素 map で返す。 */
    private Map<String, Object> refData(String ref) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ref", BlockRef.echo(ref));
        return data;
    }
}