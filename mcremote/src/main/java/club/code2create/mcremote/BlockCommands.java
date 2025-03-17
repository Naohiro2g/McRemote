package club.code2create.mcremote;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Location;
import java.util.logging.Logger;

public class BlockCommands {
    private static final Logger logger = Logger.getLogger("McR_Block"); // Logger for logging messages
    private final RemoteSession session;
    private final MiscCommands miscCommands;

    public BlockCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
    }

    public void handleBlockCommands(String command, String[] args) {
        World world = session.getOrigin().getWorld();
        switch (command) {
            case "world.getBlock":
                handleGetBlock(world, args);
                break;
            case "world.getBlocks":
                handleGetBlocks(world, args);
                break;
            case "world.getBlockWithData":
                handleGetBlockWithData(world, args);
                break;
            case "world.setBlock":
                handleSetBlock(world, args);
                break;
            case "world.setBlocks":
                handleSetBlocks(world, args);
                break;

            default:
                sendAndLogError("No such command: " + command);
                break;
        }
    }

    public void handleGetBlock(World world, String[] args) {
        if (args.length != 3) {
            sendAndLogWarning("Invalid arguments for getBlock command.");
            return;
        }
        try {
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Block block = world.getBlockAt(loc);
            session.send(block.getType().name());
        } catch (NumberFormatException e) {
            sendAndLogWarning("Invalid coordinates for getBlock command.");
        }
    }

    public void handleGetBlocks(World world, String[] args) {
        if (args.length != 6) {
            sendAndLogWarning("Invalid arguments for getBlocks command.");
            return;
        }
        try {
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

    public void handleGetBlockWithData(World world, String[] args) {
        if (args.length != 3) {
            sendAndLogWarning("Invalid arguments for getBlockWithData command.");
            return;
        }
        try {
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Block block = world.getBlockAt(loc);
            session.send(block.getType().name() + "," + block.getBlockData().getAsString());
        } catch (NumberFormatException e) {
            sendAndLogWarning("Invalid coordinates for getBlockWithData command.");
        }
    }

    public void handleSetBlock(World world, String[] args) {
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
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Material material = Material.matchMaterial(args[3]);
            String msgError = "";
            if (material == null) {
                material = Material.valueOf("AMETHYST_BLOCK"); // material for debugging
                msgError = "No such material: " + args[3] + " for setBlock. "
                        + "Location: (" + args[0] + ", " + args[1] + ", " + args[2] + ")";
            }
            int facing = args.length > 4 ? Integer.parseInt(args[4]) : 0;
            if (facing < 0 || facing >= BlockFace.values().length) {
                msgError += "  Invalid facing value for setBlock command.";
            }
            BlockFace blockFace = BlockFace.values()[facing];
            updateBlock(world, loc, material, blockFace);
            if (msgError.isEmpty()) {
                String msg = "Block " + material.name() + " Chatset successfully at: " + loc.toString();
                session.send(msg);
            } else {
                sendAndLogWarning(msgError);
            }
        } catch (NumberFormatException e) {
            sendAndLogWarning("Invalid coordinates or facing for world.setBlock command.");
        } catch (IllegalArgumentException e) {
            sendAndLogWarning("Invalid material for setBlock command.");
        } catch (Exception e) {
            sendAndLogError("Unknown error for setBlock with material " + args[3] + " at (" + args[0] + ", " + args[1] + ", " + args[2] + ")\n" + e.getMessage());
        }
    }

    public void handleSetBlocks(World world, String[] args) {
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
            Location loc1 = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Location loc2 = miscCommands.parseRelativeBlockLocation(args[3], args[4], args[5]);
            Material material = Material.matchMaterial(args[6]);
            String msgError = "";
            if (material == null) {
                material = Material.valueOf("BEACON"); // material for debugging
                msgError = "No such material: " + args[6] + " for setBlocks. Location: ("
                        + args[0] + ", " + args[1] + ", " + args[2] + ") - ("
                        + args[3] + ", " + args[4] + ", " + args[5] + ")";
            }
            int facing = args.length > 7 ? Integer.parseInt(args[7]) : 0;
            if (facing < 0 || facing >= BlockFace.values().length) {
                msgError += "  Invalid facing value for setBlocks command.";
            }
            BlockFace blockFace = BlockFace.values()[facing];
            setCuboid(world, loc1, loc2, material, blockFace);
            if (msgError.isEmpty()) {
                String msg = "Blocks " + material.name() + " set successfully at: ("
                        + args[0] + ", " + args[1] + ", " + args[2] + ") - ("
                        + args[3] + ", " + args[4] + ", " + args[5] + ")";
                session.send(msg);
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

    // updates a block
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
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    updateBlock(world, x, y, z, blockType, blockFace);
                }
            }
        }
    }

    private boolean isInvalidCoordinate(String xStr, String yStr, String zStr) {
        final int WORLD_LIMIT = 1000000; // 1 million blocks. MC's world limit is 30 million blocks.
        final int SKY_LIMIT = 1000; // MC's sky limit is 320 at version 1.18 and later.
        try {
            int x = Integer.parseInt(xStr);
            int y = Integer.parseInt(yStr);
            int z = Integer.parseInt(zStr);
            return x < -WORLD_LIMIT || x > WORLD_LIMIT
                    || y < 0 || y > SKY_LIMIT
                    || z < -WORLD_LIMIT || z > WORLD_LIMIT;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void sendAndLogWarning(String msg) {
        session.send("Error: " + msg);
        logger.warning(msg);
    }

    private void sendAndLogError(String msg) {
        session.send("Error: " + msg);
        logger.severe(msg);
    }


}

