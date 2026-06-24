package club.code2create.mcremote;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;

import java.util.logging.Logger;

public class BlockEditCommands {
    private static final Logger logger = Logger.getLogger("McR_BlockEdit");
    private static final boolean debug = false;

    private final RemoteSession session;
    private final MiscCommands miscCommands;

    public BlockEditCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
    }

    public void handleSetBlock(String[] args) {
        if (args.length < 4) {
            sendAndLogWarning("Invalid arguments for setBlock command.");
            return;
        }

        if (isInvalidCoordinate(args[0], args[1], args[2])) {
            sendAndLogWarning("Coordinates out of range for setBlock command. Location: ("
                    + args[0] + ", " + args[1] + ", " + args[2] + ")");
            return;
        }

        try {
            World world = session.getOrigin().getWorld();
            Location targetLoc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Material material = Material.matchMaterial(args[3]);
            String msgError = "";
            if (material == null) {
                material = Material.valueOf("AMETHYST_BLOCK");
                msgError = "No such material: " + args[3] + " for setBlock. "
                        + "Location: (" + args[0] + ", " + args[1] + ", " + args[2] + ")";
            }
            int facing = args.length > 4 ? Integer.parseInt(args[4]) : 0;
            if (facing < 0 || facing >= BlockFace.values().length) {
                msgError += "  Invalid facing value for setBlock command.";
            }
            BlockFace blockFace = BlockFace.values()[facing];

            if (checkRange(targetLoc)) {
                updateBlock(world, targetLoc, material, blockFace);
            } else {
                if (debug) {
                    String msg = "Block placement denied: out of allowed range for "
                            + session.getPlayerCommands().getPlayerName();
                    session.send(msg);
                }
                return;
            }

            if (msgError.isEmpty()) {
                if (debug) {
                    String msg = "Block " + material.name() + " set successfully at: " + targetLoc;
                    session.send(msg);
                }
            } else {
                sendAndLogWarning(msgError);
            }
        } catch (NumberFormatException e) {
            sendAndLogWarning("Invalid coordinates or facing for world.setBlock command.");
        } catch (IllegalArgumentException e) {
            sendAndLogWarning("Invalid material for setBlock command.");
        } catch (Exception e) {
            sendAndLogError("Unknown error for setBlock with material " + args[3]
                    + " at (" + args[0] + ", " + args[1] + ", " + args[2] + ")\n" + e.getMessage());
        }
    }

    public void handleSetBlocks(String[] args) {
        if (args.length < 7) {
            sendAndLogWarning("Invalid arguments for setBlocks command.");
            return;
        }

        if (isInvalidCoordinate(args[0], args[1], args[2]) || isInvalidCoordinate(args[3], args[4], args[5])) {
            sendAndLogWarning("Coordinates out of range for setBlocks. Location: ("
                    + args[0] + ", " + args[1] + ", " + args[2] + ") - ("
                    + args[3] + ", " + args[4] + ", " + args[5] + ")");
            return;
        }

        try {
            World world = session.getOrigin().getWorld();
            Location loc1 = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Location loc2 = miscCommands.parseRelativeBlockLocation(args[3], args[4], args[5]);
            Material material = Material.matchMaterial(args[6]);
            String msgError = "";
            if (material == null) {
                material = Material.valueOf("BEACON");
                msgError = "No such material: " + args[6] + " for setBlocks. Location: ("
                        + args[0] + ", " + args[1] + ", " + args[2] + ") - ("
                        + args[3] + ", " + args[4] + ", " + args[5] + ")";
            }
            int facing = args.length > 7 ? Integer.parseInt(args[7]) : 0;
            if (facing < 0 || facing >= BlockFace.values().length) {
                msgError += "  Invalid facing value for setBlocks command.";
            }
            BlockFace blockFace = BlockFace.values()[facing];

            if (checkRange(loc1) && checkRange(loc2)) {
                setCuboid(world, loc1, loc2, material, blockFace);
            } else {
                msgError = "Block placement denied: out of allowed range for "
                        + session.getPlayerCommands().getPlayerName();
            }

            if (msgError.isEmpty()) {
                if (debug) {
                    String msg = "Blocks " + material.name() + " set successfully at: ("
                            + args[0] + ", " + args[1] + ", " + args[2] + ") - ("
                            + args[3] + ", " + args[4] + ", " + args[5] + ")";
                    session.send(msg);
                }
            } else {
                sendAndLogWarning(msgError);
            }
        } catch (NumberFormatException e) {
            sendAndLogWarning("Invalid coordinates or facing for setBlocks command.");
        } catch (IllegalArgumentException e) {
            sendAndLogWarning("Invalid material for setBlocks command.");
        } catch (Exception e) {
            sendAndLogError("Unknown error for setBlocks command with material " + args[6]
                    + " at (" + args[0] + ", " + args[1] + ", " + args[2] + ") - ("
                    + args[3] + ", " + args[4] + ", " + args[5] + ")\n" + e.getMessage());
        }
    }

    private boolean checkRange(Location targetLoc) {
        if (session == null) {
            sendAndLogWarning("Session is null.");
            return false;
        }
        Location origin = session.getOrigin();
        if (origin == null) {
            sendAndLogWarning("Build origin not set.");
            return false;
        }
        int allowedRange = resolveBuildRange();
        double dx = Math.abs(targetLoc.getX() - origin.getX());
        double dz = Math.abs(targetLoc.getZ() - origin.getZ());
        return dx <= allowedRange && dz <= allowedRange;
    }

    /**
     * 建築許容範囲を返す。プレイヤーが紐付いていれば PermissionManager から、
     * いなければ（setBuildOrigin 経路など）config の default_build_range を使う。
     */
    private int resolveBuildRange() {
        PlayerCommands playerCommands = session.getPlayerCommands();
        org.bukkit.OfflinePlayer player = playerCommands != null ? playerCommands.getAttachedPlayer() : null;
        if (player != null) {
            return McRemote.instance.getPermissionManager().getPlayerRange(player);
        }
        return McRemote.getInstance().getDefaultBuildRange();
    }

    private void updateBlock(World world, Location loc, Material blockType, BlockFace blockFace) {
        Block block = world.getBlockAt(loc);
        block.setType(blockType);
        BlockData blockData = block.getBlockData();
        if (blockData instanceof Directional) {
            ((Directional) blockData).setFacing(blockFace);
        }
        block.setBlockData(blockData);
    }

    private void updateBlock(World world, int x, int y, int z, Material blockType, BlockFace blockFace) {
        Location loc = new Location(world, x, y, z);
        updateBlock(world, loc, blockType, blockFace);
    }

    private void setCuboid(World world, Location loc1, Location loc2, Material blockType, BlockFace blockFace) {
        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int minY = Math.min(loc1.getBlockY(), loc2.getBlockY());
        int maxY = Math.max(loc1.getBlockY(), loc2.getBlockY());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = maxY; y >= minY; y--) {
                for (int z = minZ; z <= maxZ; z++) {
                    updateBlock(world, x, y, z, blockType, blockFace);
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
                    || y < 0 || y > skyLimit
                    || z < -worldLimit || z > worldLimit;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void sendAndLogWarning(String msg) {
        if (debug) {
            session.send("Error: " + msg);
        }
        logger.warning(msg);
    }

    private void sendAndLogError(String msg) {
        if (debug) {
            session.send("Error: " + msg);
        }
        logger.severe(msg);
    }
}
