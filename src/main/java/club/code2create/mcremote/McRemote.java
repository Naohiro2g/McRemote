package club.code2create.mcremote;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class McRemote extends JavaPlugin implements Listener {
    private static final Logger logger = Logger.getLogger("McRemote");
    private final RightClickDeduplicator rightClickDeduplicator = new RightClickDeduplicator();

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
    private CredentialService credentialService;
    private PairingManager pairingManager;
    private boolean authEnforcement;
    private int maxSessionsPerUuid;
    // b3 resource catalog は registry が確立した plugin enable 時に一度だけ生成し、全 session で共有する。
    private CatalogService catalogService;
    private B5RuntimePolicy b5RuntimePolicy;
    private WorkAdmission workAdmission;

    @Override
    public void onEnable(){
        instance = this;
        this.saveDefaultConfig();
        FileConfiguration config = this.getConfig();
        migrateMissingConfigDefaults(config);
        removeDeprecatedCredentialConfig(config);

        // 認証ストアを serverThread 起動前に用意する（接続到来時の RemoteSession ctor が参照するため）。
        // enforcement 既定 OFF＝token 無し hello 通過（3リポ非同期着地・§6.5/§10.11.1 item5）。
        this.authEnforcement = config.getBoolean("auth.enforcement", false);
        logger.info("Auth enforcement: " + this.authEnforcement);
        long pairCodeTtl = config.getLong("auth.pair_code_ttl_seconds", 120);
        long sessionTokenTtl = config.getLong("auth.session_token_ttl_seconds", 7200);
        this.maxSessionsPerUuid = Math.max(1, config.getInt("auth.max_sessions_per_uuid", 16));
        logger.info("Max sessions per UUID: " + this.maxSessionsPerUuid);
        int maxLongLivedCredentials = Math.max(1,
                config.getInt("auth.max_long_lived_credentials_per_uuid", 16));
        Path snapshotPath = resolveCredentialPath(config.getString(
                "auth.credential_store_path", "credential-store/snapshot.json"));
        Path authorityPath = resolveCredentialPath(config.getString(
                "auth.revocation_authority_path", "credential-revocations"));
        this.credentialService = new CredentialService(
                snapshotPath, authorityPath, maxLongLivedCredentials);
        logger.info("Credential domain health: " + credentialService.health()
                + " (" + credentialService.healthDetail() + ")");
        this.tokenStore = new TokenStore(credentialService);
        this.pairingManager = new PairingManager(tokenStore, pairCodeTtl, sessionTokenTtl);
        this.catalogService = new CatalogService();
        this.b5RuntimePolicy = B5RuntimePolicy.from(config);
        this.workAdmission = new WorkAdmission(b5RuntimePolicy);
        logger.info("b5 connection command queue capacity: "
                + b5RuntimePolicy.connectionQueueCapacity());
        logger.info("Resource catalog ready: blocks=" + catalogService.getBlockCount()
                + " entities=" + catalogService.getEntityCount()
                + " particles=" + catalogService.getParticleCount()
                + " bytes=" + catalogService.getSerializedBytes()
                + " hash=" + catalogService.getCatalogHash());
        PluginCommand mcremoteCommand = getCommand("mcremote");
        if (mcremoteCommand != null) {
            PairCommand command = new PairCommand(this, pairingManager, credentialService);
            mcremoteCommand.setExecutor(command);
            mcremoteCommand.setTabCompleter(command);
        } else {
            logger.warning("Command 'mcremote' not registered in plugin.yml; /mcremote pair unavailable");
        }

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

        // 認証・認可の依存を全て初期化してから socket を公開する。
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

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new TickHandler(), 1, 1);
        getServer().getScheduler().runTaskTimerAsynchronously(
                this, credentialService::reconcileIfNeeded, 200L, 200L);
        saveResources();
    }

    private Path resolveCredentialPath(String configured) {
        Path value = Path.of(configured == null ? "" : configured.trim());
        return value.isAbsolute() ? value.normalize()
                : getDataFolder().toPath().resolve(value).toAbsolutePath().normalize();
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

    private void removeDeprecatedCredentialConfig(FileConfiguration config) {
        if (config.contains("auth.player_token_ttl_seconds")) {
            config.set("auth.player_token_ttl_seconds", null);
            saveConfig();
            logger.info("Removed deprecated auth.player_token_ttl_seconds; "
                    + "long-lived credentials use expires_at=null until explicit revoke");
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
        if (serverThread != null) {
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

    public CredentialService getCredentialService() {
        return credentialService;
    }

    /** b3 resource catalog snapshot（plugin enable 時に生成、全 session 共通）。 */
    public CatalogService getCatalogService() {
        return this.catalogService;
    }

    B5RuntimePolicy getB5RuntimePolicy() {
        return b5RuntimePolicy;
    }

    WorkAdmission getWorkAdmission() {
        return workAdmission;
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

    /** revoke/logout の線形化後、同じ credential で認証された全 session を終了対象へ mark。 */
    public void closeSessionsForCredential(UUID credentialId) {
        for (RemoteSession session : sessions) {
            if (credentialId.equals(session.getBoundCredentialId())) {
                session.requestCloseAfterFlush();
            }
        }
    }

    /** explicit reset は全 long-lived credential を失効させるため既存 session も閉じる。 */
    public void closeAllLongLivedSessions() {
        for (RemoteSession session : sessions) {
            if (session.getBoundTokenType() == TokenStore.TokenType.LONG_LIVED) {
                session.requestCloseAfterFlush();
            }
        }
    }

    @NullMarked
    private class TickHandler implements Runnable {
        @Override
        public void run() {
            workAdmission.beginTick();
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

    /**
     * protocol 23 replaces b5's item-agnostic {@code block_right_click} with {@code pickaxe_poke}
     * (DECISIONS 2026-08-26-06): a finite event ring should not be spent on ordinary play (chests,
     * doors, crafting tables), so capture is gated on holding a {@link Tag#ITEMS_PICKAXES} item.
     * This is purely observational — {@code ignoreCancelled=true} and no result/use-item mutation
     * here — so the target block's own vanilla interaction (opening a chest, a door, etc.) still
     * happens unimpeded.
     */
    @EventHandler(ignoreCancelled=true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !Tag.ITEMS_PICKAXES.isTagged(item.getType())) {
            return;
        }
        Block clicked = event.getClickedBlock();
        EquipmentSlot hand = event.getHand();
        if (!rightClickDeduplicator.accept(
                event.getPlayer().getUniqueId(),
                DimensionResolver.canonical(clicked.getWorld()),
                clicked.getX(), clicked.getY(), clicked.getZ(),
                Bukkit.getCurrentTick(), hand)) {
            return;
        }
        for (RemoteSession session : sessionsFor(event.getPlayer())) {
            Location origin = capturedOrigin(session);
            session.queueCapturedEvent(B5EventDto.pickaxePoke(
                    DimensionResolver.canonical(clicked.getWorld()),
                    blockPosition(origin),
                    List.of(
                            clicked.getX() - origin.getBlockX(),
                            clicked.getY() - origin.getBlockY(),
                            clicked.getZ() - origin.getBlockZ()),
                    event.getBlockFace().name().toLowerCase(Locale.ROOT),
                    BlockCodec.encode(clicked.getBlockData()),
                    hand == EquipmentSlot.OFF_HAND ? "off" : "main",
                    item.getType().getKey().toString()));
        }
    }

    @EventHandler
    public void onChatPosted(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        for (RemoteSession session : sessionsFor(event.getPlayer())) {
            Location origin = capturedOrigin(session);
            session.queueCapturedEvent(B5EventDto.chatPosted(
                    DimensionResolver.canonical(event.getPlayer().getWorld()), blockPosition(origin), message));
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter)) {
            return;
        }
        for (RemoteSession session : sessionsFor(shooter)) {
            captureProjectileHit(session, event);
        }
    }

    private void captureProjectileHit(RemoteSession session, ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Location origin = capturedOrigin(session);
        Location hit = projectile.getLocation();
        Map<String, Object> target = new LinkedHashMap<>();
        if (event.getHitBlock() != null) {
            Block block = event.getHitBlock();
            target.put("kind", "block");
            target.put("block", BlockCodec.encode(block.getBlockData()));
            target.put("pos", List.of(
                    block.getX() - origin.getBlockX(),
                    block.getY() - origin.getBlockY(),
                    block.getZ() - origin.getBlockZ()));
            if (event.getHitBlockFace() != null) {
                target.put("face", event.getHitBlockFace().name().toLowerCase(Locale.ROOT));
            }
        } else if (event.getHitEntity() instanceof Player) {
            target.put("kind", "player");
        } else if (event.getHitEntity() != null) {
            target.put("kind", "entity");
            try {
                target.put("handle", session.issueEntityHandle(event.getHitEntity()));
            } catch (EntityHandleRegistry.CapacityException e) {
                session.recordEventCapacityDrop();
                return;
            }
        } else {
            return;
        }

        session.queueCapturedEvent(B5EventDto.projectileHit(
                DimensionResolver.canonical(hit.getWorld()),
                blockPosition(origin),
                List.of(
                        WireNumbers.position(hit.getX() - origin.getX()),
                        WireNumbers.position(hit.getY() - origin.getY()),
                        WireNumbers.position(hit.getZ() - origin.getZ())),
                projectile.getType().getKey().toString(),
                target));
    }

    private List<RemoteSession> sessionsFor(Player player) {
        UUID uuid = player.getUniqueId();
        List<RemoteSession> matching = new ArrayList<>();
        for (RemoteSession session : sessions) {
            if (!session.pendingRemoval && session.isHelloComplete() && uuid.equals(session.getBoundUuid())) {
                matching.add(session);
            }
        }
        return matching;
    }

    private static Location capturedOrigin(RemoteSession session) {
        Location origin = session.getOrigin();
        if (origin == null || origin.getWorld() == null) {
            throw new IllegalStateException("authenticated session has no build origin");
        }
        return origin.clone();
    }

    private static List<Integer> blockPosition(Location location) {
        return List.of(location.getBlockX(), location.getBlockY(), location.getBlockZ());
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
