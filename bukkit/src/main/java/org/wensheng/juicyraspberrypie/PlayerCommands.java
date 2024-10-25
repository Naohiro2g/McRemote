package org.wensheng.juicyraspberrypie;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.util.UUID;
import java.util.logging.Logger;

public class PlayerCommands {
    private static final Logger logger = Logger.getLogger("PlayerCommands"); // Logger for logging messages
    private RemoteSession session;

    public PlayerCommands(RemoteSession session) {
        this.session = session;
    }

    public void handleSetPlayerCommand(String[] args) {
        // Process if the command arguments are 1, 4, or 5. Otherwise, return an error message and exit.
        if (args.length != 1 && args.length != 4 && args.length != 5) {
            logger.warning("Invalid arguments for setPlayer command.");
            session.send("Error: Invalid arguments for setPlayer command.");
            return;
        }

        String playerName = args[0];
        Player attachedPlayer = Bukkit.getPlayer(playerName);

        // Check if the player is online.
        if (attachedPlayer == null) {
            // If the player is offline, search for the player in the server data.
            UUID playerUUID = getPlayerUUID(playerName);
            if (playerUUID != null) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
                if (offlinePlayer.hasPlayedBefore()) {
                    attachedPlayer = offlinePlayer.getPlayer();
                }
            }
            // If not found, return an error message "No record of the player visiting."
            if (attachedPlayer == null) {
                logger.warning("No record of the player visiting.");
                session.send("Error: No record of the player visiting. Bye.");
                return;
            }
        }

        // If attachedPlayer is set, get the custom origin. It may not exist.
        Location customOrigin = getCustomOriginLocation(attachedPlayer);
        int x = 0, y = 0, z = 0;
        String worldName = "world";

        if (customOrigin != null) {
            // If the custom origin exists, get x, y, z, and world. Log the information.
            x = customOrigin.getBlockX();
            y = customOrigin.getBlockY();
            z = customOrigin.getBlockZ();
            worldName = customOrigin.getWorld().getName();
            logger.info("Custom origin found for player: " + playerName + " at location: " + customOrigin + " in world: " + worldName);
        }

        // If the command arguments are 4 or 5, overwrite with the arguments.
        if (args.length >= 4) {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        }

        if (args.length == 5) {
            World world = Bukkit.getWorld(args[4]);
            if (world == null) {
                session.send("Error: " + args[4] + " is invalid world name.");
                return;
            } else {
                worldName = args[4];
            }
        }

        // Create a Location, call setCustomOriginLocation, and update.
        World world = Bukkit.getWorld(worldName);
        Location location = new Location(world, x, y, z);
        setCustomOriginLocation(attachedPlayer, location);

        // Set the Location to the session's origin.
        session.setOrigin(location);

        // Log the information. Start the session with player_name, x, y, z, and world.
        logger.info("Session started for player: " + playerName + " at location: " + location);
        session.send("Player " + playerName + " set to location: " + x + ", " + y + ", " + z + " in world \"" + worldName + "\"");
        return;
    }

    private UUID getPlayerUUID(String playerName) {
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName().equalsIgnoreCase(playerName)) {
                return offlinePlayer.getUniqueId();
            }
        }
        return null;
    }

    public void setCustomOriginLocation(Player player, Location location) {
        NamespacedKey keyX = new NamespacedKey(session.getPlugin(), "custom_origin_x");
        NamespacedKey keyY = new NamespacedKey(session.getPlugin(), "custom_origin_y");
        NamespacedKey keyZ = new NamespacedKey(session.getPlugin(), "custom_origin_z");
        NamespacedKey keyWorld = new NamespacedKey(session.getPlugin(), "custom_origin_world");

        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(keyX, PersistentDataType.INTEGER, location.getBlockX());
        data.set(keyY, PersistentDataType.INTEGER, location.getBlockY());
        data.set(keyZ, PersistentDataType.INTEGER, location.getBlockZ());
        data.set(keyWorld, PersistentDataType.STRING, location.getWorld().getName());
    }

    public Location getCustomOriginLocation(Player player) {
        NamespacedKey keyX = new NamespacedKey(session.getPlugin(), "custom_origin_x");
        NamespacedKey keyY = new NamespacedKey(session.getPlugin(), "custom_origin_y");
        NamespacedKey keyZ = new NamespacedKey(session.getPlugin(), "custom_origin_z");
        NamespacedKey keyWorld = new NamespacedKey(session.getPlugin(), "custom_origin_world");

        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.has(keyX, PersistentDataType.INTEGER) && data.has(keyY, PersistentDataType.INTEGER) && data.has(keyZ, PersistentDataType.INTEGER) && data.has(keyWorld, PersistentDataType.STRING)) {
            int x = data.get(keyX, PersistentDataType.INTEGER);
            int y = data.get(keyY, PersistentDataType.INTEGER);
            int z = data.get(keyZ, PersistentDataType.INTEGER);
            String worldName = data.get(keyWorld, PersistentDataType.STRING);
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                return new Location(world, x, y, z);
            } else {
                logger.warning("World " + worldName + " not found. Using default world.");
                world = Bukkit.getWorld("world");
                if (world != null) {
                    return new Location(world, x, y, z);
                }
            }
        }
        return null;
    }
}
