package club.code2create.mcremote;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.logging.Logger;

public class McRemote extends JavaPlugin implements Listener {
    final Logger logger = Logger.getLogger("McR");
    private static final Set<Material> blockBreakDetectionTools = EnumSet.of(
            Material.DIAMOND_SWORD,
            Material.GOLDEN_SWORD,
            Material.IRON_SWORD,
            Material.STONE_SWORD,
            Material.WOODEN_SWORD);

    private ServerListenerThread serverThread;
    private final List<RemoteSession> sessions = new ArrayList<>();
    private static boolean luckPermsEnabled = false;
    public static McRemote instance;
    private IPermissionManager permissionManager;

    @Override
    public void onEnable(){
        instance = this;
        this.saveDefaultConfig();
        FileConfiguration config = this.getConfig();

        // APIポート設定を読み込み、サーバースレッドを起動
        int port = config.getInt("api_port");
        try {
            serverThread = new ServerListenerThread(this, new InetSocketAddress(port));
            new Thread(serverThread).start();
            getLogger().info("Server started at port " + port);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            getLogger().warning(sw.toString());
            getLogger().warning("Failed to start Server");
            return;
        }

        // イベント登録や定期処理のスケジュール
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new TickHandler(), 1, 1);
        saveResources();

        // config.yml から権限・meta 関連の設定を読み込む
        String onlinePermission = config.getString("luckperm_permissions.online", "mcr.online");
        String offlinePermission = config.getString("luckperm_permissions.offline", "mcr.offline");
        String buildRangeMetaKey = config.getString("luckperm_permissions.build.range", "mcr.build.range");
        int defaultBuildRange = config.getInt("default_build_range", 32);

        // LuckPerms の有無をチェック　→　存在するなら LuckPermsPermissionManager を生成
        luckPermsEnabled = (Bukkit.getPluginManager().getPlugin("LuckPerms") != null);
        if (luckPermsEnabled) {
            logger.info("initializing PermissionManager (LuckPermsPermissionManager)");
            this.permissionManager = new LuckPermsPermissionManager(this, onlinePermission, offlinePermission, buildRangeMetaKey);
        } else {
            logger.info("initializing FallbackPermissionManager");
            this.permissionManager = new FallbackPermissionManager(onlinePermission, offlinePermission, defaultBuildRange);
        }
        logger.info("PermissionManager instance: " + this.permissionManager);
        // ここ以降、permissionManager を利用して各種処理を実施…
    }

    private void saveResources(){
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()){
            this.saveResource("config.yml", false);
        }
    }

    @Override
    public void onDisable(){
        getServer().getScheduler().cancelTasks(this);
        for (RemoteSession session: sessions) {
            try {
                session.close();
            } catch (Exception e) {
                logger.warning("Failed to close RemoteSession");
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                logger.warning(sw.toString());
            }
        }
        serverThread.running = false;
        try {
            serverThread.serverSocket.close();
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
        }
        serverThread = null;
    }

    public IPermissionManager getPermissionManager() {
        return this.permissionManager;
    }

    @NullMarked
    private class TickHandler implements Runnable {
        @Override
        public void run() {
            Iterator<RemoteSession> sI = sessions.iterator();
            while (sI.hasNext()) {
                RemoteSession s = sI.next();
                if (s.pendingRemoval) {
                    s.close();
                    sI.remove();
                } else {
                    s.tick();
                }
            }
        }
    }

    @EventHandler(ignoreCancelled=true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack currentTool = event.getItem();
        if (currentTool == null || !blockBreakDetectionTools.contains(currentTool.getType())) {
            return;
        }
        for (RemoteSession session: sessions) {
            session.queuePlayerInteractEvent(event);
        }
    }

    @EventHandler
    public void onChatPosted(AsyncChatEvent event) {
        for (RemoteSession session: sessions) {
            session.queueChatPostedEvent(event);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        for (RemoteSession session: sessions) {
            session.queueProjectileHitEvent(event);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event){
        for (RemoteSession session: sessions) {
            session.handlePlayerQuitEvent();
        }
    }

    /**
     * 接続された新規セッションを処理する
     */
    void handleConnection(RemoteSession newSession) {
        if (checkBanned(newSession)) {
            logger.warning("Kicking " + newSession.getSocket().getRemoteSocketAddress() +
                    " because the IP address has been banned.");
            newSession.kick("You've been banned from this server!");
            return;
        }
        synchronized(sessions) {
            sessions.add(newSession);
        }
    }

    private boolean checkBanned(RemoteSession session) {
        Set<String> ipBans = getServer().getIPBans();
        String sessionIp = session.getSocket().getInetAddress().getHostAddress();
        return ipBans.contains(sessionIp);
    }

    public static McRemote getInstance() {
        return instance;
    }

    /**
     * 静的に LuckPerms の有無を返す
     */
    public static boolean isLuckPermsEnabled() {
        return luckPermsEnabled;
    }
}
