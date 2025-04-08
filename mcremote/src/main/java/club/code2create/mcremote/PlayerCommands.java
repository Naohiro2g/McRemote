package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import java.util.UUID;
import java.util.logging.Logger;

public class PlayerCommands {
    private static final Logger logger = Logger.getLogger("McR_Player");
    private final RemoteSession session;

    // プレイヤー情報を保持するフィールド
    private UUID playerUUID;
    private String playerName;
    private int playerRange; // build.range のキャッシュ
    private Location origin;

    public PlayerCommands(RemoteSession session) {
        this.session = session;
    }

    /**
     * setPlayer コマンドの処理。
     * 形式：
     *    setPlayer(playerName, x, y, z)
     * または
     *    setPlayer(playerName, x, y, z, world)
     * オンライン／オフライン問わず、コマンドで渡された座標を採用し、
     * RemoteSession での起点 (origin) を更新します。
     */
    public void handleSetPlayerCommand(String[] args) {
        // 引数は 4 個または 5 個でなければならない
        if (args.length != 4 && args.length != 5) {
            logger.warning("Invalid arguments for setPlayer command. Bye.");
            session.send("Error: Invalid arguments for setPlayer command. Bye.");
            return;
        }

        String pName = args[0];
        UUID uuid = getPlayerUUID(pName);
        if (!checkPlayer(pName, uuid)) {
            return;
        }

        // プレイヤー情報のフィールドを更新
        this.playerName = pName;
        this.playerUUID = uuid;

        logger.info("Player " + playerName + " with UUID: " + playerUUID + " is requesting new session.");
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);

        // build.range をキャッシュ
        logger.info("Player " + playerName + " has played before: " + offlinePlayer.hasPlayedBefore());
        try {
            this.playerRange = McRemote.instance.getPermissionManager().getPlayerRange(offlinePlayer);
        } catch (Exception e) {
            logger.warning("Failed to get player range: " + e.getMessage());
            session.send("Error: Failed to get player range. Bye.");
            return;
        }
//        this.playerRange = PermissionManager.getPlayerRange(offlinePlayer);
        logger.info("Player " + playerName + " has range: " + playerRange);

        if (McRemote.isLuckPermsEnabled()) {
            if (offlinePlayer.isOnline()) {
                if (McRemote.instance.getPermissionManager().canConstructOnline(offlinePlayer)) {
                    logger.info("Player " + playerName + " is online and allowed Minecraft Remote online.");
                } else {
                    logger.warning("Player " + playerName + " is online but not allowed Minecraft Remote even online.");
                    session.send("Error: Player " + playerName + " is online but not allowed Minecraft Remote. Bye.");
                    return;
                }
            } else {
                logger.info("Player " + playerName + " is offline but has played before.");
                if (McRemote.getInstance().getPermissionManager().canConstructOffline(offlinePlayer)) {
                    logger.info("Allowed Minecraft Remote offline for player " + playerName);
                } else {
                    logger.warning("Player " + playerName + " is not allowed Minecraft Remote offline. Bye.");
                    session.send("Error: Player " + playerName + " is not allowed Minecraft Remote offline. Bye.");
                    return;
                }
            }
        } else {
            logger.warning("LuckPerms is not available. Allowing player " + playerName + " to connect.");
        }


        int x, y, z;
        String worldName = "world";
        World world;
        // オンライン・オフライン問わず、コマンド引数の座標をパースする
        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            session.send("Error: x, y, z must be integers.");
            logger.warning("Invalid coordinate values in setPlayer command. Bye.");
            return;
        }
        if (args.length == 5) {
            world = Bukkit.getWorld(args[4]);
            if (world == null) {
                session.send("Error: " + args[4] + " is an invalid world name. Bye.");
                return;
            }
            worldName = args[4];
        } else {
            world = Bukkit.getWorld(worldName);
        }

        this.origin = new Location(world, x, y, z);
        session.setOrigin(origin);

        logger.warning("Session started for player: " + playerName + " at \n" + this.origin);
        session.send("Player " + playerName + " set to location: " + x + ", " + y + ", " + z + " in world \"" + worldName + "\"");
    }

    private boolean checkPlayer(String playerName, UUID playerUUID) {
        if (playerUUID == null) {
            session.send("Error: Player " + playerName + " not found. Bye.");
            logger.warning("Player " + playerName + " not found. Bye.");
            return false;
        }
        return true;
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

    /**
     * オフラインプレイヤーも含め、セッションに紐付けられた最新のプレイヤー情報を返します。
     */
    public OfflinePlayer getAttachedPlayer() {
        if (playerUUID == null) {
            return null;
        }
        return Bukkit.getOfflinePlayer(playerUUID);
    }

    /**
     * セッションに紐付いたプレイヤー名を返します。
     */
    public String getPlayerName() {
        return playerName;
    }

    /**
     * セッションの起点 (origin) を返します。
     */
    public Location getOrigin() {
        return origin;
    }

    /**
     * プレイヤーの建築範囲 (build.range) をキャッシュから返します。
     */
    public int getPlayerRange(OfflinePlayer offlinePlayer) {
        return playerRange;
    }
}