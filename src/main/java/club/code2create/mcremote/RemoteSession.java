package club.code2create.mcremote;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import net.kyori.adventure.text.Component;

public class RemoteSession implements CommandDispatchContext, BuildContextSession, B7CommandContext {
    private static final int MAX_COMMANDS_PER_TICK = 1000;
    private static final Logger logger = Logger.getLogger("McR_RemoteSession");
    // world_constants の nullable 値等を出すため serializeNulls（§6.2 フィールド常在）。
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
    private TokenStore.TokenType boundTokenType = null;
    private UUID boundCredentialId = null;
    private final Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private Thread inThread;
    private Thread outThread;
    private final ConnectionCommandQueue inQueue;
    private final ConnectionFrameQueue outQueue;
    private volatile boolean running = true;
    private volatile boolean closingAfterFlush = false;
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);
    private final McRemote plugin;
    private final UUID connectionEpoch = UUID.randomUUID();

    // 通知メカニズム用のロックオブジェクト
    private final Object queueLock = new Object();

    private final EventRing eventRing;
    private final EntityHandleRegistry entityHandles;

    static final int MAX_EVENT_POLL_RESPONSE_BYTES = 61_440;

    // コマンド処理担当の各クラス
    private final PlayerCommands playerCommands;
    private final BlockCommands blockCommands;
    private final MiscCommands miscCommands;
    private final BuildStateCommands buildStateCommands;
    private final CatalogCommands catalogCommands;
    private final CommandParser commandParser;
    private final CommandDispatcher commandDispatcher;
    // pre-hello の auth.* 経路（§6.5）。ペアリングは hello の前段ゆえ門番より前に通す。
    private final AuthCommands authCommands;

    public RemoteSession(McRemote plugin, Socket socket) throws IOException {
        this.plugin = plugin;
        this.socket = socket;
        DimensionResolver dimensions = new DimensionResolver();
        this.playerCommands = new PlayerCommands(this, dimensions);
        this.miscCommands = new MiscCommands(this);
        this.blockCommands = new BlockCommands(this, miscCommands);
        this.buildStateCommands = new BuildStateCommands(this, dimensions);
        this.catalogCommands = new CatalogCommands(this, plugin.getCatalogService());
        B5RuntimePolicy b5Policy = plugin.getB5RuntimePolicy();
        this.inQueue = new ConnectionCommandQueue(b5Policy.connectionQueueCapacity());
        this.outQueue = new ConnectionFrameQueue(b5Policy.connectionResponseQueueCapacity());
        this.eventRing = new EventRing(
                b5Policy.eventRingCapacity(),
                b5Policy.eventRingBytes(),
                resultPayloadBudget(Integer.MAX_VALUE, MAX_EVENT_POLL_RESPONSE_BYTES));
        this.entityHandles = new EntityHandleRegistry(b5Policy.entityHandleCapacity());
        EventCommands eventCommands = new EventCommands(
                this,
                eventRing,
                b5Policy.eventPollDefault(),
                b5Policy.eventPollLimit());
        WorldB5Commands worldB5Commands = new WorldB5Commands(this, entityHandles, b5Policy);
        SignCommands signCommands = new SignCommands(this, miscCommands);
        DirectionCommands directionCommands = new DirectionCommands(
                this, entityHandles, plugin.getPermissionManager());
        LightningCommands lightningCommands = new LightningCommands(
                this,
                plugin.getPermissionManager(),
                plugin.getLightningRateAdmission(),
                plugin.getLightningRuntimePolicy());
        // build state は identity から分離。既定は minecraft:overworld / (200,0,200)。
        this.origin = buildStateCommands.defaultOrigin();
        this.commandParser = new CommandParser();
        this.commandDispatcher = new CommandDispatcher(this, new RemoteCommandRegistrar().createRegistry(
                this, blockCommands, miscCommands, buildStateCommands, catalogCommands,
                eventCommands, worldB5Commands, signCommands, directionCommands, lightningCommands));
        this.authCommands = new AuthCommands(
                this, plugin.getPairingManager(), plugin.getCredentialService());
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

    @Override
    public UUID getConnectionEpoch() {
        return connectionEpoch;
    }

    public TokenStore.TokenType getBoundTokenType() {
        return boundTokenType;
    }

    public UUID getBoundCredentialId() {
        return boundCredentialId;
    }

    private CommandOutcome handleLine(String line) {
        activeId = null;
        try {
            ParsedCommand parsed = commandParser.parse(line);
            // 要求 id を相関キーに据える（応答／エラー封筒で使う。null＝notification）。
            activeId = parsed.getId();
            if (!helloComplete) {
                // ペアリングは hello の前段の独立メソッド（§6.5）。auth.* を先に捌き、
                // 対応したら門番を通さない（helloComplete は立てない・close しない）。
                if (authCommands.handlePreHello(parsed)) {
                    return CommandOutcome.COMPLETED;
                }
                handleHello(parsed);
                return CommandOutcome.COMPLETED;
            }
            if (authCommands.handleAuthenticated(parsed)) {
                return CommandOutcome.COMPLETED;
            }
            commandDispatcher.dispatch(parsed);
            return CommandOutcome.COMPLETED;
        } catch (CommandDeferredException e) {
            return CommandOutcome.DEFERRED;
        } catch (IllegalArgumentException e) {
            // 非 JSON／不正 JSON-RPC 行は破棄（wire-format-design §2）。
            // hello 前なら門番として切断、確立後は1行捨てて継続（堅牢性）。
            logger.warning("Discarded malformed line: " + e.getMessage());
            if (!helloComplete) {
                close();
            }
            return CommandOutcome.COMPLETED;
        } finally {
            activeId = null;
        }
    }

    private enum CommandOutcome { COMPLETED, DEFERRED }

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
            TokenStore.ResolveResult resolution = plugin.getTokenStore().resolve(token);
            if (resolution.status() == TokenStore.ResolveStatus.STORE_UNAVAILABLE) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", resolution.operation().wireName());
                respondError(-32000, "credential_store_unavailable", data);
                logger.warning("Hello rejected: credential_store_unavailable operation="
                        + resolution.operation().wireName());
                requestCloseAfterFlush();
                return;
            }
            if (resolution.status() != TokenStore.ResolveStatus.ACTIVE) {
                if (enforce) {
                    String reason = switch (resolution.status()) {
                        case EXPIRED -> "token_expired";
                        case REVOKED -> "token_revoked";
                        case NOT_FOUND -> "token_not_found";
                        default -> "token_invalid";
                    };
                    respondError(-32000, reason, null);
                    logger.warning("Hello rejected: " + reason + " (enforcement ON)");
                    requestCloseAfterFlush();
                    return;
                }
            } else {
                TokenStore.TokenRecord tokenRecord = resolution.record();
                UUID uuid = tokenRecord.uuid();
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
                boundTokenType = tokenRecord.tokenType();
                boundCredentialId = tokenRecord.credentialId();
                playerCommands.bind(uuid);
            }
        }
        try {
            // build.dimension と origin は全項目の検証・解決後に一体反映する。
            origin = buildStateCommands.resolveHelloBuild(parsed.getParams());
        } catch (BuildStateCommands.InvalidBuildException e) {
            respondError(-32602, "invalid_params", null);
            logger.warning("Hello rejected: invalid build context");
            close();
            return;
        } catch (BuildStateCommands.UnknownDimensionException e) {
            respondError(-32000, "unknown_dimension", BuildStateCommands.dimensionData(e.dimension()));
            logger.warning("Hello rejected: unknown_dimension");
            close();
            return;
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

    /** hello params（protocol 22 object形, §6.1）から protocol を取り出す。 */
    private String extractHelloProtocol(JsonElement params) {
        if (params == null || !params.isJsonObject()) {
            return null;
        }
        JsonElement p = params.getAsJsonObject().get("protocol");
        return (p != null && p.isJsonPrimitive()) ? p.getAsString().trim() : null;
    }

    /**
     * hello 応答の flat result（wire-format-design §6.2）。
     * 版フィールドは clean な protocol semver、catalogHash は b3 で実値、
     * y_sea は world_constants に束ねる。dimension/origin はserver正準build context。
     */
    private Map<String, Object> buildHelloResult() {
        String mcVersion = Bukkit.getMinecraftVersion();
        List<String> supported = plugin.getConfig().getStringList("supported_mc_versions");
        if (supported.isEmpty()) {
            supported = List.of(mcVersion);
        }
        // y_sea は座標式に使わない情報定数。world 不明時は number|null の null（§6.2 / DECISIONS 2026-07-02-02）。
        Integer ySea = (origin != null && origin.getWorld() != null)
                ? origin.getWorld().getSeaLevel() - 1
                : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", ProtocolInfo.PROTOCOL);
        result.put("mc_version", mcVersion);
        result.put("supported_mc_versions", supported);
        // world/profile 依存の情報定数は world_constants bucket に束ねる（top-level に散らさない・§6.2）。
        Map<String, Object> worldConstants = new LinkedHashMap<>();
        worldConstants.put("y_sea", ySea);
        result.put("world_constants", worldConstants);
        result.put("catalogHash", plugin.getCatalogService().getCatalogHash());
        // auth 済みなら束縛 UUID を返す（§6.2・token→player 束縛ゆえ spoofing 不可）。
        if (boundUuid != null) {
            result.put("player", boundUuid.toString());
        }
        if (origin != null && origin.getWorld() != null) {
            result.putAll(BuildStateCommands.buildContext(origin));
        }
        // permissions bucket（§6.2）＝UUID→LuckPerms の scopes。auth 済みのときのみ。
        if (boundUuid != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(boundUuid);
            IPermissionManager perms = plugin.getPermissionManager();
            result.put("permissions", buildPermissions(perms, op));
        }
        return result;
    }

    static Map<String, Object> buildPermissions(IPermissionManager permissions, OfflinePlayer player) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("online", permissions.canConstructOnline(player));
        result.put("offline", permissions.canConstructOffline(player));
        result.put("buildRange", permissions.getPlayerRange(player));
        return result;
    }

    public void close() {
        pendingRemoval = true;
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        running = false;
        eventRing.clear();
        entityHandles.clear();

        // 出力スレッドを待機中の場合は通知して解除
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        if (inThread != null && Thread.currentThread() != inThread) {
            inThread.interrupt();
        }
        try {
            if (Thread.currentThread() != inThread) {
                inThread.join(2000);
            }
            if (Thread.currentThread() != outThread) {
                outThread.join(2000);
            }
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

    /** 既に queue 済みの成功応答を flush してから transport を閉じる。 */
    public void requestCloseAfterFlush() {
        closingAfterFlush = true;
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
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

    boolean queueCapturedEvent(Map<String, Object> event) {
        return eventRing.offer(event);
    }

    void recordEventCapacityDrop() {
        eventRing.dropForCapacity();
    }

    String issueEntityHandle(Entity entity) {
        return entityHandles.issue(entity);
    }

    @Override
    public WorkAdmission.Result admitWork(int units) {
        return plugin.getWorkAdmission().admit(connectionEpoch, boundUuid, units);
    }

    /**
     * Applies b5 work admission to a setter before world access. A temporarily pressured
     * notification stays at the FIFO head because it has no response channel for retry advice.
     */
    @Override
    public boolean admitSetterWork(long units) {
        WorkAdmission.Result result = units > Integer.MAX_VALUE
                ? WorkAdmission.Result.WORK_LIMIT_EXCEEDED
                : admitWork((int) units);
        if (result == WorkAdmission.Result.ACCEPTED) {
            return true;
        }
        if (result == WorkAdmission.Result.BACKPRESSURE && activeId == null) {
            throw CommandDeferredException.INSTANCE;
        }
        sessionWorkError(result);
        return false;
    }

    @Override
    public boolean rejectTemporaryBackpressure() {
        if (activeId == null) {
            throw CommandDeferredException.INSTANCE;
        }
        respondError(-32000, "backpressure", null);
        return false;
    }

    private void sessionWorkError(WorkAdmission.Result result) {
        respondError(-32000,
                result == WorkAdmission.Result.BACKPRESSURE
                        ? "backpressure" : "work_limit_exceeded",
                null);
    }

    @Override
    public boolean hasConstructionPermission() {
        if (boundUuid == null) {
            return true;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(boundUuid);
        Player online = Bukkit.getPlayer(boundUuid);
        return selectConstructionPermission(plugin.getPermissionManager(), player, online);
    }

    static boolean selectConstructionPermission(
            IPermissionManager permissions,
            OfflinePlayer player,
            Player online
    ) {
        return online != null && online.isOnline()
                ? permissions.canConstructOnline(player)
                : permissions.canConstructOffline(player);
    }

    @Override
    public boolean isWithinBuildRange(Location target) {
        if (origin == null || target == null) {
            return false;
        }
        int range = boundUuid == null
                ? plugin.getDefaultBuildRange()
                : plugin.getPermissionManager().getPlayerRange(Bukkit.getOfflinePlayer(boundUuid));
        return withinBuildRange(origin, target, range);
    }

    static boolean withinBuildRange(Location origin, Location target, int range) {
        return Math.abs(target.getX() - origin.getX()) <= range
                && Math.abs(target.getZ() - origin.getZ()) <= range;
    }

    void tick() {
        if (closingAfterFlush) {
            return;
        }
        int maxCommandsPerTick = MAX_COMMANDS_PER_TICK;
        int processedCount = 0;
        String message;
        while ((message = inQueue.peek()) != null) {
            CommandOutcome outcome = handleLine(message);
            if (outcome == CommandOutcome.DEFERRED) {
                break;
            }
            String removed = inQueue.removeHead();
            if (removed == null) {
                throw new IllegalStateException("connection FIFO head disappeared");
            }
            processedCount++;
            if (pendingRemoval || closingAfterFlush) {
                break;
            }
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
                        inQueue.put(newLine);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                } catch (Exception e) {
                    if (running) {
                        StringWriter sw = new StringWriter();
                        e.printStackTrace(new PrintWriter(sw));
                        logger.warning(sw.toString());
                    }
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
                        while (running && outQueue.isEmpty() && !closingAfterFlush) {
                            queueLock.wait();
                        }
                        // 終了フラグが立っていて、キューが空であれば終了
                        if ((!running || closingAfterFlush) && outQueue.isEmpty()) {
                            running = false;
                            break;
                        }
                        line = outQueue.poll();
                    }
                    // 取り出したデータが存在する場合は書き込む
                    if (line != null) {
                        out.write(line);
                        out.write('\n');
                        out.flush();
                        synchronized (queueLock) {
                            if (closingAfterFlush && outQueue.isEmpty()) {
                                running = false;
                            }
                        }
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
            if (closingAfterFlush) {
                pendingRemoval = true;
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warning("Failed to close credential-revoked socket: " + e.getMessage());
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
        enqueue(encodeResultResponse(activeId, value));
    }

    static String encodeResultResponse(int id, Object value) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.put("result", value);
        return GSON.toJson(env);
    }

    static int resultPayloadBudget(int id, int maxResponseBytes) {
        int nullPayloadBytes = GSON.toJson(null).getBytes(StandardCharsets.UTF_8).length;
        int envelopeBytes = encodeResultResponse(id, null)
                .getBytes(StandardCharsets.UTF_8).length - nullPayloadBytes;
        int budget = maxResponseBytes - envelopeBytes;
        if (budget < 1) {
            throw new IllegalArgumentException("response byte limit cannot hold a JSON-RPC envelope");
        }
        return budget;
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

    /** 直列化済み1行を有限出力キューへ。飽和は無言dropせずconnection failureにする。 */
    private void enqueue(String line) {
        if (pendingRemoval) {
            return;
        }
        boolean accepted;
        synchronized (queueLock) {
            accepted = outQueue.offer(line);
            if (accepted) {
                queueLock.notify();
            }
        }
        if (!accepted) {
            failOutputTransport();
        }
    }

    private void failOutputTransport() {
        logger.warning("Bounded response queue saturated; failing connection "
                + socket.getRemoteSocketAddress());
        pendingRemoval = true;
        running = false;
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        if (inThread != null) {
            inThread.interrupt();
        }
        if (outThread != null) {
            outThread.interrupt();
        }
        try {
            socket.close();
        } catch (IOException e) {
            logger.warning("Failed to close saturated response transport: " + e.getMessage());
        }
    }
}
