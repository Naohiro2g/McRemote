package club.code2create.mcremote;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class McRemote extends JavaPlugin implements Listener {
    private static final Logger logger = Logger.getLogger("McRemote");
    private static final Set<Material> blockBreakDetectionTools = EnumSet.of(
            Material.DIAMOND_SWORD,
            Material.GOLDEN_SWORD,
            Material.IRON_SWORD,
            Material.STONE_SWORD,
            Material.WOODEN_SWORD);

    private ServerListenerThread serverThread;
    // 追加はリスナースレッド、反復（TickHandler / イベント handler）は主スレッドで並行。
    // snapshot 反復で ConcurrentModificationException を起こさない CopyOnWriteArrayList を使う。
    private final List<RemoteSession> sessions = new CopyOnWriteArrayList<>();
    private static boolean luckPermsEnabled = false;
    public static McRemote instance;
    private IPermissionManager permissionManager;
    private int defaultBuildRange;
    // 認証（wire §6.5）：pairing/token の正本は plugin 常駐。複数 session スレッド＋/mcremote pair の
    // 主スレッドから共有アクセスされるため concurrent 実装（169e64f の CME 教訓）。
    private TokenStore tokenStore;
    private PairingManager pairingManager;
    private boolean authEnforcement;
    private int maxSessionsPerUuid;

    @Override
    public void onEnable(){
        instance = this;
        this.saveDefaultConfig();
        FileConfiguration config = this.getConfig();
        migrateMissingConfigDefaults(config);

        // 認証ストアを serverThread 起動前に用意する（接続到来時の RemoteSession ctor が参照するため）。
        // enforcement 既定 OFF＝token 無し hello 通過（3リポ非同期着地・§6.5/§10.11.1 item5）。
        this.authEnforcement = config.getBoolean("auth.enforcement", false);
        logger.info("Auth enforcement: " + this.authEnforcement);
        long pairCodeTtl = config.getLong("auth.pair_code_ttl_seconds", 120);
        long sessionTokenTtl = config.getLong("auth.session_token_ttl_seconds", 7200);
        long playerTokenTtl = config.getLong("auth.player_token_ttl_seconds", 0);
        this.maxSessionsPerUuid = Math.max(1, config.getInt("auth.max_sessions_per_uuid", 16));
        logger.info("Max sessions per UUID: " + this.maxSessionsPerUuid);
        this.tokenStore = new TokenStore();
        this.pairingManager = new PairingManager(tokenStore, pairCodeTtl, sessionTokenTtl, playerTokenTtl);
        PluginCommand mcremoteCommand = getCommand("mcremote");
        if (mcremoteCommand != null) {
            mcremoteCommand.setExecutor(new PairCommand(pairingManager));
        } else {
            logger.warning("Command 'mcremote' not registered in plugin.yml; /mcremote pair unavailable");
        }

        // APIポート設定を読み込み、サーバースレッドを起動
        int port = config.getInt("api_port");
        try {
            serverThread = new ServerListenerThread(this, new InetSocketAddress(port));
            new Thread(serverThread).start();
            logger.info("Server started at port " + port);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
            logger.warning("Failed to start Server");
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
        this.defaultBuildRange = defaultBuildRange;

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

    private void migrateMissingConfigDefaults(FileConfiguration config) {
        Configuration defaults = config.getDefaults();
        if (defaults == null) {
            return;
        }

        List<String> added = new ArrayList<>();
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path)) {
                continue;
            }
            if (!config.contains(path, true)) {
                config.set(path, defaults.get(path));
                added.add(path);
            }
        }

        if (!added.isEmpty()) {
            saveConfig();
            logger.info("Added missing config defaults: " + String.join(", ", added));
        }
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

    public int getDefaultBuildRange() {
        return this.defaultBuildRange;
    }

    public IPermissionManager getPermissionManager() {
        return this.permissionManager;
    }

    /** ペアリング state machine の正本（§6.5）。RemoteSession の pre-hello auth.* 経路が使う。 */
    public PairingManager getPairingManager() {
        return this.pairingManager;
    }

    /** 発行済み token ストア（hash のみ保存・§6.5）。hello 検証（次ステップ）が使う。 */
    public TokenStore getTokenStore() {
        return this.tokenStore;
    }

    /** enforcement トグル（§10.11.1 item5）。ON で hello が token 必須になる（次ステップで参照）。 */
    public boolean isAuthEnforcement() {
        return this.authEnforcement;
    }

    /** 同一 UUID の同時認証済み session 上限（versioning §10.11.1 item7）。 */
    public int getMaxSessionsPerUuid() {
        return this.maxSessionsPerUuid;
    }

    /** 現在 hello 済みで当該 UUID に束縛されている live session 数。 */
    public int countBoundSessions(UUID uuid) {
        int count = 0;
        for (RemoteSession session : sessions) {
            if (!session.pendingRemoval && session.isHelloComplete() && uuid.equals(session.getBoundUuid())) {
                count++;
            }
        }
        return count;
    }

    @NullMarked
    private class TickHandler implements Runnable {
        @Override
        public void run() {
            // CopyOnWriteArrayList の反復は snapshot。要素除去はリスト側 remove(Object) で行う
            // （snapshot iterator は remove() 非対応）。RemoteSession は equals 未override＝同一性判定。
            for (RemoteSession s : sessions) {
                if (s.pendingRemoval) {
                    s.close();
                    sessions.remove(s);
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
        // CopyOnWriteArrayList.add は原子的なので外部同期は不要。
        sessions.add(newSession);
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
