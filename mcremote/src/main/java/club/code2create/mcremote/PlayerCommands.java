package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import java.util.UUID;
import java.util.logging.Logger;


public class PlayerCommands {
    private static final Logger logger = Logger.getLogger("McR_Player"); // Logger for logging messages
    private final RemoteSession session;

    public PlayerCommands(RemoteSession session) {
        this.session = session;
    }

    public void handleSetPlayerCommand(String[] args) {
        // Process if the command arguments are 4, or 5. Otherwise, return an error message and exit.
        if (args.length != 4 && args.length != 5) {
            logger.warning("Invalid arguments for setPlayer command. Bye.");
            session.send("Error: Invalid arguments for setPlayer command. Bye.");
            return;  // disconnect
        }

        String playerName = args[0];
        UUID playerUUID = getPlayerUUID(playerName);
        if (!checkPlayer(playerName, playerUUID)) {return;}  // disconnect

        logger.info("Player " + playerName + " with UUID: " + playerUUID + " is requesting new session.");
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);

        if (McRemote.isLuckPermsEnabled()) {
            if (offlinePlayer.isOnline()) {  // player is online
                if (PermissionManager.canConstructOnline(offlinePlayer.getPlayer())) {
                    logger.info("Player " + playerName + " is online and allowed Minecraft Remote online.");
                } else {
                    logger.warning("Player " + playerName + " is online but not allowed Minecraft Remote even online.");
                    session.send("Error: Player " + playerName + " is online but not allowed Minecraft Remote. Bye.");
                    return;  // disconnect
                }
            } else {                        // player is offline
                logger.info("Player " + playerName + " is offline but has played before.");
                if (PermissionManager.canConstructOffline(offlinePlayer)) {
                    logger.info("  and allowed Minecraft Remote even offline.");
                } else {
                    logger.warning("Player " + playerName + " is not allowed Minecraft Remote offline. Bye.");
                    session.send("Error: Player " + playerName + " is not allowed Minecraft Remote offline. Bye.");
                    return;  // disconnect
                }
            }
        } else {
            logger.warning("LuckPerms is not available. Allowing player " + playerName + " to connect.");
        }

        int x, y, z;
        String worldName = "world";

        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            session.send("Error: x, y, z must be integers.");
            logger.warning("Invalid values for setPlayer command. Bye.");
            return;
        }

        if (args.length == 5) {
            World world = Bukkit.getWorld(args[4]);
            if (world == null) {
                session.send("Error: " + args[4] + " is invalid world name. Bye.");
                return;
            } else {
                worldName = args[4];
            }
        }

        World world = Bukkit.getWorld(worldName);
        Location location = new Location(world, x, y, z);
        session.setOrigin(location);  // this is the setPlayer command

        // Log the information. Start the session with player_name, x, y, z, and world.
        logger.warning("Session started for player: " + playerName + " at \n" + location);
        session.send("Player " + playerName + " set to location: " + x + ", " + y + ", " + z + " in world \"" + worldName + "\"");
    }


    private boolean checkPlayer(String playerName, UUID playerUUID) {
        if (playerUUID == null) {
            session.send("Error: Player " + playerName + " not found. Bye.");
            logger.warning("Player " + playerName + " not found. Bye.");
            return false;
        } else {
            return true;
        }
    }

    private UUID getPlayerUUID(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(playerName)) {
                return offlinePlayer.getUniqueId();
            }
        }
        return null;
    }
}
