package org.wensheng.juicyraspberrypie;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Location;
import java.util.logging.Logger;

public class BlockCommands {
    private static final Logger logger = Logger.getLogger("MCR_Block"); // Logger for logging messages
    private RemoteSession session;
    private MiscCommands miscCommands;

    public BlockCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
    }

    public void handleCommand(String command, String[] args) {
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
                session.send("No such block command: " + command);
                logger.warning("No such block command: " + command);
                break;
        }
    }

    public void handleGetBlock(World world, String[] args) {
        if (args.length != 3) {
            session.send("Invalid arguments for world.getBlock command.");
            logger.warning("Invalid arguments for world.getBlock command.");
            return;
        }
        try {
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Block block = world.getBlockAt(loc);
            session.send(block.getType().name());
        } catch (NumberFormatException e) {
            session.send("Invalid coordinates for world.getBlock command.");
            logger.warning("Invalid coordinates for world.getBlock command.");
        }
    }

    public void handleGetBlocks(World world, String[] args) {
        if (args.length != 6) {
            session.send("Invalid arguments for world.getBlocks command.");
            logger.warning("Invalid arguments for world.getBlocks command.");
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
            session.send("Invalid coordinates for world.getBlocks command.");
            logger.warning("Invalid coordinates for world.getBlocks command.");
        }
    }

    public void handleGetBlockWithData(World world, String[] args) {
        if (args.length != 3) {
            session.send("Invalid arguments for world.getBlockWithData command.");
            logger.warning("Invalid arguments for world.getBlockWithData command.");
            return;
        }
        try {
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Block block = world.getBlockAt(loc);
            session.send(block.getType().name() + "," + block.getBlockData().getAsString());
        } catch (NumberFormatException e) {
            session.send("Invalid coordinates for world.getBlockWithData command.");
        }
    }

    public void handleSetBlock(World world, String[] args) {
        if (args.length < 4) {
            session.send("Invalid arguments for world.setBlock command.");
            logger.warning("Invalid arguments for world.setBlock command.");
            return;
        }
        try {
            Location loc = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Material material = Material.matchMaterial(args[3]);
            if (material == null) {
                material = Material.valueOf("SANDSTONE");
                logger.warning("non such material: " + args[3]);
            }
            int facing = args.length > 4 ? Integer.parseInt(args[4]) : 0;
            BlockFace blockFace = BlockFace.values()[facing];
            updateBlock(world, loc, material, blockFace);
            // session.send("Block set successfully.");
        } catch (NumberFormatException e) {
            session.send("Invalid coordinates or facing for world.setBlock command.");
            logger.warning("Invalid coordinates or facing for world.setBlock command.");
        } catch (IllegalArgumentException e) {
            session.send("Invalid material for world.setBlock command.");
            logger.warning("Invalid material for world.setBlock command.");
        }
    }

    public void handleSetBlocks(World world, String[] args) {
        if (args.length < 7) {
            session.send("Invalid arguments for world.setBlocks command.");
            logger.warning("Invalid arguments for world.setBlocks command.");
            return;
        }
        try {
            Location loc1 = miscCommands.parseRelativeBlockLocation(args[0], args[1], args[2]);
            Location loc2 = miscCommands.parseRelativeBlockLocation(args[3], args[4], args[5]);
            Material material = Material.matchMaterial(args[6]);
            if (material == null) {
                material = Material.valueOf("SANDSTONE");
            }
            int facing = args.length > 7 ? Integer.parseInt(args[7]) : 0;
            BlockFace blockFace = BlockFace.values()[facing];
            setCuboid(world, loc1, loc2, material, blockFace);
            // session.send("Blocks set successfully.");
        } catch (NumberFormatException e) {
            session.send("Invalid coordinates or facing for world.setBlocks command.");
            logger.warning("Invalid coordinates or facing for world.setBlocks command.");
        } catch (IllegalArgumentException e) {
            session.send("Invalid material for world.setBlocks command.");
            logger.warning("Invalid material for world.setBlocks command.");
        }
    }

    // updates a block
    private void updateBlock(World world, Location loc, Material blockType, BlockFace blockFace) {
        Block block = world.getBlockAt(loc);
        block.setType(blockType);
        BlockData blockData = block.getBlockData();
        if(blockData instanceof Directional){
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
}
