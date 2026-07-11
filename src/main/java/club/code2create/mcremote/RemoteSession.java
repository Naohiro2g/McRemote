package club.code2create.mcremote;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.ProjectileHitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import net.kyori.adventure.text.Component;

public class RemoteSession {
    private static final int MAX_COMMANDS_PER_TICK = 1000;
    private static final Logger logger = Logger.getLogger("McR_RemoteSession");
    // catalogHash:null 等を出すため serializeNulls（§6.2 フィールド常在）。
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    public boolean pendingRemoval = false;
    private Location origin = null;
    // hello（§8）が済むまでコマンドを受け付けない＝サーバが入口の門番（無言 bot を弾く）
    private boolean helloComplete = false;
    // 処理中の要求の JSON-RPC id（応答／エラー封筒の相関キー）。null＝notification。
    private Integer activeId = null;
    private Player attachedPlayer = null;
    // hello の auth 検証で束縛した UUID（§6.1/§6.2）。enforcement ON では必須、OFF でも token 提示・解決時に束縛。
    private UUID boundUuid = null;
    private final Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private Thread inThread;
    private Thread outThread;
    private final ArrayDeque<String> inQueue = new ArrayDeque<>();
    private final ArrayDeque<String> outQueue = new ArrayDeque<>();
    private boolean running = true;
    private final McRemote plugin;

    // 通知メカニズム用のロックオブジェクト
    private final Object queueLock = new Object();

    // イベント用キュー
    private final ArrayDeque<PlayerInteractEvent> interactEventQueue = new ArrayDeque<>();
    private final ArrayDeque<AsyncChatEvent> chatPostedQueue = new ArrayDeque<>();
    private final ArrayDeque<ProjectileHitEvent> projectileHitQueue = new ArrayDeque<>();

    // コマンド処理担当の各クラス
    private final PlayerCommands playerCommands;
    private final BlockCommands blockCommands;
    private final MiscCommands miscCommands;
    private final EntityCommands entityCommands;
    private final BuildStateCommands buildStateCommands;
    private final CommandParser commandParser;
    private final CommandDispatcher commandDispatcher;
    // pre-hello の auth.* 経路（§6.5）。ペアリングは hello の前段ゆえ門番より前に通す。
    private final AuthCommands authCommands;

    public RemoteSession(McRemote plugin, Socket socket) throws IOException {
        this.plugin = plugin;
        this.socket = socket;
        this.playerCommands = new PlayerCommands(this);
        this.miscCommands = new MiscCommands(this);
        this.entityCommands = new EntityCommands(this, miscCommands);
        this.blockCommands = new BlockCommands(this, miscCommands);
        this.buildStateCommands = new BuildStateCommands(this);
        // build state は identity から分離（setPlayer 撤去）。接続時点で既定原点を持たせる
        // （overworld / (200,0,200)）ので、クライアントは setBuildOrigin 無しでも建築できる。
        this.origin = buildStateCommands.defaultOrigin();
        this.commandParser = new CommandParser();
        this.commandDispatcher = new CommandDispatcher(this, new RemoteCommandRegistrar().createRegistry(
                this, blockCommands, miscCommands, entityCommands, buildStateCommands));
        this.authCommands = new AuthCommands(this, plugin.getPairingManager());
        init();
    }

    private void init() throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setTrafficClass(0x10);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        startThreads();
    }

    private void startThreads() {
        inThread = new Thread(new InputThread());
        inThread.start();
        outThread = new Thread(new OutputThread());
        outThread.start();
        logger.info("Started input and output threads.");
    }

    public Socket getSocket() {
        return socket;
    }

    public McRemote getPlugin() {
        return plugin;
    }

    public void setOrigin(Location origin) {
        this.origin = origin;
    }

    public Location getOrigin() {
        return this.origin;
    }

    public PlayerCommands getPlayerCommands() {
        return playerCommands;
    }

    public BlockCommands getBlockCommands() { return blockCommands; }

    public boolean isHelloComplete() {
        return helloComplete;
    }

    public UUID getBoundUuid() {
        return boundUuid;
    }

    private void handleLine(String line) {
        try {
            ParsedCommand parsed = commandParser.parse(line);
            // 要求 id を相関キーに据える（応答／エラー封筒で使う。null＝notification）。
            activeId = parsed.getId();
            if (!helloComplete) {
                // ペアリングは hello の前段の独立メソッド（§6.5）。auth.* を先に捌き、
                // 対応したら門番を通さない（helloComplete は立てない・close しない）。
                if (authCommands.handle(parsed)) {
                    return;
                }
                handleHello(parsed);
                return;
            }
            commandDispatcher.dispatch(parsed);
        } catch (IllegalArgumentException e) {
            // 非 JSON／不正 JSON-RPC 行は破棄（wire-format-design §2）。
            // hello 前なら門番として切断、確立後は1行捨てて継続（堅牢性）。
            logger.warning("Discarded malformed line: " + e.getMessage());
            if (!helloComplete) {
                close();
            }
        }
    }

    /**
     * hello ネゴシエーション（wire-format-design §6 / versioning-design §8）。接続後の最初の1行を処理する。
     * クライアント主導：クライアントが object params で要求 protocol を名乗り、サーバが §8.2 で判定する。
     *  - hello 以外の最初の method → 拒否して切断（門番）。
     *  - 非互換 → protocol_mismatch エラーを返して切断（§8.3, error 写像は暫定）。
     *  - 互換 → flat 応答（§6.2）で版／ワールド定数を返し、以降の method を解禁。
     */
    private void handleHello(ParsedCommand parsed) {
        if (!"hello".equals(parsed.getName())) {
            respondError(-32600, "expected_hello", null);
            logger.warning("Pre-hello method rejected: " + parsed.getName());
            close();
            return;
        }
        String clientProtocol = extractHelloProtocol(parsed.getParams());
        if (clientProtocol == null) {
            respondError(-32602, "protocol_required", null);
            logger.warning("Malformed hello: missing protocol in params");
            close();
            return;
        }
        if (!ProtocolInfo.isCompatible(clientProtocol)) {
            // 暫定写像（DECISION_CANDIDATE 2026-06-27, §6.3/§8.3 の対応表確定待ち）。
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("server", ProtocolInfo.PROTOCOL);
            data.put("client_requires", clientProtocol);
            respondError(-32600, "protocol_mismatch", data);
            logger.warning("Protocol mismatch: server=" + ProtocolInfo.PROTOCOL + " client=" + clientProtocol);
            close();
            return;
        }
        // hello auth 検証（§6.1/§6.3・versioning §10.11.1 item5 enforcement トグル）。
        // 検証と強制を分離：OFF（dev 既定）は token 欠落/無効を許容し b1 疎通を保つ（提示され解決できれば UUID 束縛）。
        // ON は 欠落→auth_required・無効→token_invalid・認可拒否→permission_denied（token 温存）。
        // error は -32000 帯＋data.reason（既存 pair error と同形。code/message 確定は §7.3 ratify 後）。
        String token = extractHelloToken(parsed.getParams());
        boolean enforce = plugin.isAuthEnforcement();
        if (token == null || token.isEmpty()) {
            if (enforce) {
                respondError(-32000, "auth_required", null);
                logger.warning("Hello rejected: auth_required (enforcement ON, no token)");
                close();
                return;
            }
        } else {
            Optional<TokenStore.TokenRecord> rec = plugin.getTokenStore().resolve(token);
            if (rec.isEmpty()) {
                if (enforce) {
                    respondError(-32000, "token_invalid", null);
                    logger.warning("Hello rejected: token_invalid (enforcement ON)");
                    close();
                    return;
                }
            } else {
                UUID uuid = rec.get().uuid();
                // 認可は常に UUID→LuckPerms（item4・不在時は FallbackPermissionManager が許可）。
                // ON のときのみ hello を gate：online/offline いずれの建築権も無ければ拒否。
                if (enforce) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                    IPermissionManager perms = plugin.getPermissionManager();
                    if (!perms.canConstructOnline(op) && !perms.canConstructOffline(op)) {
                        respondError(-32000, "permission_denied", null);
                        logger.warning("Hello rejected: permission_denied uuid=" + uuid);
                        close(); // token は温存（resolve のみ・revoke しない）
                        return;
                    }
                }
                int maxSessions = plugin.getMaxSessionsPerUuid();
                int currentSessions = plugin.countBoundSessions(uuid);
                if (currentSessions >= maxSessions) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("limit", maxSessions);
                    data.put("current", currentSessions);
                    respondError(-32000, "too_many_sessions", data);
                    logger.warning("Hello rejected: too_many_sessions uuid=" + uuid
                            + " current=" + currentSessions + " limit=" + maxSessions);
                    close();
                    return;
                }
                boundUuid = uuid;
                playerCommands.bind(uuid);
            }
        }
        respondResult(buildHelloResult());
        helloComplete = true;
        logger.info("hello OK (client protocol " + clientProtocol + ", advertising " + ProtocolInfo.PROTOCOL
                + (boundUuid != null ? ", player " + boundUuid : ", no auth") + ")");
    }

    /** hello params（object 形, §6.1）から {@code auth.token} を取り出す。無ければ null。 */
    private String extractHelloToken(JsonElement params) {
        if (params == null || !params.isJsonObject()) {
            return null;
        }
        JsonElement auth = params.getAsJsonObject().get("auth");
        if (auth == null || !auth.isJsonObject()) {
            return null;
        }
        JsonElement t = auth.getAsJsonObject().get("token");
        return (t != null && t.isJsonPrimitive()) ? t.getAsString().trim() : null;
    }

    /** hello params（object 形, §6.1）から protocol を取り出す。配列形は移行中クライアント向けに許容。 */
    private String extractHelloProtocol(JsonElement params) {
        if (params == null) {
            return null;
        }
        if (params.isJsonObject()) {
            JsonElement p = params.getAsJsonObject().get("protocol");
            return (p != null && p.isJsonPrimitive()) ? p.getAsString().trim() : null;
        }
        if (params.isJsonArray() && !params.getAsJsonArray().isEmpty()) {
            JsonElement p = params.getAsJsonArray().get(0);
            return p.isJsonPrimitive() ? p.getAsString().trim() : null;
        }
        return null;
    }

    /**
     * hello 応答の flat result（wire-format-design §6.2）。
     * 版フィールドは clean な protocol semver、catalogHash は b1 で常在 null、
     * y_sea は world_constants に束ねる（DECISIONS 2026-07-02-02）。world/origin は接続時の build state。
     */
    private Map<String, Object> buildHelloResult() {
        String mcVersion = Bukkit.getMinecraftVersion();
        List<String> supported = plugin.getConfig().getStringList("supported_mc_versions");
        if (supported.isEmpty()) {
            supported = List.of(mcVersion);
        }
        // y_sea は座標式に使わない情報定数。world 不明時は number|null の null（§6.2 / DECISIONS 2026-07-02-02）。
        Integer ySea = (origin != null && origin.getWorld() != null) ? origin.getWorld().getSeaLevel() : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", ProtocolInfo.PROTOCOL);
        result.put("mc_version", mcVersion);
        result.put("supported_mc_versions", supported);
        // world/profile 依存の情報定数は world_constants bucket に束ねる（top-level に散らさない・§6.2）。
        Map<String, Object> worldConstants = new LinkedHashMap<>();
        worldConstants.put("y_sea", ySea);
        result.put("world_constants", worldConstants);
        result.put("catalogHash", null); // catalogHash は b2 でも常在 null（実値化は b3・versioning item14）
        // auth 済みなら束縛 UUID を返す（§6.2・token→player 束縛ゆえ spoofing 不可）。
        if (boundUuid != null) {
            result.put("player", boundUuid.toString());
        }
        if (origin != null && origin.getWorld() != null) {
            result.put("world", origin.getWorld().getName());
            result.put("origin", List.of(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()));
        }
        // permissions bucket（§6.2）＝UUID→LuckPerms の scopes。auth 済みのときのみ。
        if (boundUuid != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(boundUuid);
            IPermissionManager perms = plugin.getPermissionManager();
            Map<String, Object> permissions = new LinkedHashMap<>();
            permissions.put("online", perms.canConstructOnline(op));
            permissions.put("offline", perms.canConstructOffline(op));
            permissions.put("buildRange", perms.getPlayerRange(op));
            result.put("permissions", permissions);
        }
        return result;
    }

    public void close() {
        running = false;
        pendingRemoval = true;

        // 出力スレッドを待機中の場合は通知して解除
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        try {
            inThread.join(2000);
            outThread.join(2000);
        } catch (InterruptedException e) {
            logger.warning("Failed to stop in/out thread");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
        }
        try {
            socket.close();
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
        }
        logger.info("Closed connection from " + socket.getRemoteSocketAddress() + ".");
    }

    public void handlePlayerQuitEvent() {
        if (attachedPlayer != null) {
            logger.info("Player " + attachedPlayer.getName() + " has quit.");
            attachedPlayer = null;
        }
    }

    public void kick(String reason) {
        if (attachedPlayer != null) {
            attachedPlayer.kick(Component.text(reason));
            logger.info("Player " + attachedPlayer.getName() + " was kicked for: " + reason);
            attachedPlayer = null;
        }
    }

    void queueProjectileHitEvent(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getShooter() instanceof Player) {
            projectileHitQueue.add(event);
        }
    }

    void queuePlayerInteractEvent(PlayerInteractEvent event) {
        interactEventQueue.add(event);
    }

    void queueChatPostedEvent(AsyncChatEvent event) {
        chatPostedQueue.add(event);
    }

    void tick() {
        int maxCommandsPerTick = MAX_COMMANDS_PER_TICK;
        int processedCount = 0;
        String message;
        while ((message = inQueue.poll()) != null) {
            handleLine(message);
            processedCount++;
            if (processedCount >= maxCommandsPerTick) {
                logger.warning("Over " + maxCommandsPerTick +
                        " commands were queued - deferring " + inQueue.size() + " to next tick");
                break;
            }
        }
        if (!running && inQueue.isEmpty()) {
            pendingRemoval = true;
        }
    }

    private class InputThread implements Runnable {
        @Override
        public void run() {
            logger.info("Starting input thread!");
            while (running) {
                try {
                    String newLine = in.readLine();
                    if (newLine == null) {
                        running = false;
                    } else {
                        inQueue.add(newLine);
                    }
                } catch (Exception e) {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    logger.warning(sw.toString());
                    running = false;
                }
            }
            try {
                in.close();
            } catch (Exception e) {
                logger.warning("Failed to close input buffer");
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                logger.warning(sw.toString());
            }
        }
    }

    private class OutputThread implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    String line = null;
                    // queueLockを使用してキューへのアクセスを同期
                    synchronized (queueLock) {
                        // キューが空の場合は通知を待つ
                        while (running && outQueue.isEmpty()) {
                            queueLock.wait();
                        }
                        // 終了フラグが立っていて、キューが空であれば終了
                        if (!running && outQueue.isEmpty()) {
                            break;
                        }
                        line = outQueue.poll();
                    }
                    // 取り出したデータが存在する場合は書き込む
                    if (line != null) {
                        out.write(line);
                        out.write('\n');
                        out.flush();
                    }
                } catch (InterruptedException e) {
                    // スレッドが割り込まれた場合
                    if (running) {
                        logger.warning("Output thread interrupted: " + e.getMessage());
                    }
                } catch (Exception e) {
                    if (running) {
                        StringWriter sw = new StringWriter();
                        e.printStackTrace(new PrintWriter(sw));
                        logger.warning(sw.toString());
                        running = false;
                    }
                }
            }
            try {
                out.close();
            } catch (Exception e) {
                logger.warning("Failed to close output buffer");
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                logger.warning(sw.toString());
            }
        }
    }

    /**
     * 後方互換の薄いラッパ。既存ハンドラの {@code session.send(value)} を JSON-RPC の
     * 成功応答（result）に写す。notification（id 無し）なら no-op。
     */
    public void send(String value) {
        respondResult(value);
    }

    /**
     * JSON-RPC 成功応答（§3.3）を送る。{@code value} は Gson で result スロットへ直列化する
     * （String→JSON 文字列、Map/List→object/array）。notification（activeId==null）なら応答しない（§3.2）。
     */
    public void respondResult(Object value) {
        if (activeId == null) {
            return;
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jsonrpc", "2.0");
        env.put("id", activeId);
        env.put("result", value);
        enqueue(GSON.toJson(env));
    }

    /**
     * JSON-RPC エラー応答（§3.3 / §7.3）を送る。意味は {@code data.reason}（安定 enum）が運ぶ。
     * notification（activeId==null）にはエラーも返さない（§7.3）。
     *
     * @param code      JSON-RPC code（-32601 等の予約／-32000 番台のサーバ定義域）
     * @param reason    安定 enum（message にも流用）
     * @param extraData reason 以外に載せる data（ref/server 等）。null 可
     */
    public void respondError(int code, String reason, Map<String, Object> extraData) {
        if (activeId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        if (extraData != null) {
            data.putAll(extraData);
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", reason);
        error.put("data", data);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jsonrpc", "2.0");
        env.put("id", activeId);
        env.put("error", error);
        enqueue(GSON.toJson(env));
    }

    /** 直列化済み1行を出力キューへ。終了処理中は捨てる（close 後の遅延書き込み防止）。 */
    private void enqueue(String line) {
        if (pendingRemoval) {
            return;
        }
        synchronized (queueLock) {
            outQueue.add(line);
            queueLock.notify();
        }
    }
}
