package club.code2create.mcremote;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.ProjectileHitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import net.kyori.adventure.text.Component;

public class RemoteSession {
    private static final int MAX_COMMANDS_PER_TICK = 9000;
    private static final Logger logger = Logger.getLogger("McR_RemoteSession");

    public boolean pendingRemoval = false;
    private Location origin = null;
    private Player attachedPlayer = null;  // プレイヤー認証済みなら設定する想定
    private final Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private Thread inThread;
    private Thread outThread;
    private final ArrayDeque<String> inQueue = new ArrayDeque<>();
    private final ArrayDeque<String> outQueue = new ArrayDeque<>();
    private boolean running = true;
    private final McRemote plugin;

    // イベント用キュー
    private final ArrayDeque<PlayerInteractEvent> interactEventQueue = new ArrayDeque<>();
    private final ArrayDeque<AsyncChatEvent> chatPostedQueue = new ArrayDeque<>();
    private final ArrayDeque<ProjectileHitEvent> projectileHitQueue = new ArrayDeque<>();

    // コマンド処理担当の各クラス
    private final PlayerCommands playerCommands;
    private final BlockCommands blockCommands;
    private final MiscCommands miscCommands;
    private final EntityCommands entityCommands;

    public RemoteSession(McRemote plugin, Socket socket) throws IOException {
        this.plugin = plugin;
        this.socket = socket;
        this.playerCommands = new PlayerCommands(this);
        this.miscCommands = new MiscCommands(this);
        this.entityCommands = new EntityCommands(this, miscCommands);
        this.blockCommands = new BlockCommands(this, miscCommands);
        init();
    }

    /**
     * セッションの初期化処理
     */
    private void init() throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setTrafficClass(0x10);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        startThreads();
    }

    /**
     * 入出力スレッドの開始
     */
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

    /**
     * 受信した１行をコマンドと引数に分解し、適切な処理へ委譲する。
     */
    private void handleLine(String line) {
        String[] parts = line.split("\\(", 2);
        String command = parts[0];
        String[] args = parts.length > 1 ? parts[1].substring(0, parts[1].length() - 1).split(",") : new String[0];
        handleCommand(command, args);
    }

    /**
     * コマンド文字列と引数に応じて各担当クラスの処理を呼び出す。
     * プレイヤーの起点 (origin) が未設定の場合、setPlayer コマンド以外は拒否する。
     */
    private void handleCommand(String c, String[] args) {
        try {
            if (this.origin == null && !c.equals("setPlayer")) {
                send("Error: Player and its origin are not set, please use setPlayer() first.");
                logger.severe("Player and its origin are not set. Command: " + c + ", Arguments: " + Arrays.toString(args));
                close();
                return;
            }

            switch (c) {
                case "world.getBlock":
                case "world.getBlocks":
                case "world.getBlockWithData":
                case "world.setBlock":
                case "world.setBlocks":
                    blockCommands.handleBlockCommands(c, args);
                    break;
                case "world.spawnParticle":
                    miscCommands.handleSpawnParticle(args);
                    break;
                case "world.getHeight":
                    miscCommands.handleGetHeight(origin.getWorld(), args);
                    break;
                case "chat.post":
                    miscCommands.handleChatPost(args);
                    break;
                case "world.spawnEntity":
                    miscCommands.handleSpawnEntity(args);
                    break;
                case "world.getNearbyEntities":
                case "entity.getPos":
                case "entity.setPos":
                case "entity.getRotation":
                case "entity.setRotation":
                case "entity.getPitch":
                case "entity.setPitch":
                case "entity.getYaw":
                case "entity.setYaw":
                case "entity.remove":
                    entityCommands.handleEntityCommands(c, args);
                    break;
                case "setPlayer":
                    playerCommands.handleSetPlayerCommand(args);
                    break;
                default:
                    send("Error: No such entity/player command: " + c);
                    logger.warning("No such entity/player command: " + c);
                    break;
            }
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
            close();
        }
    }

    public void send(Object a) {
        send(a.toString());
    }

    public void send(String a) {
        if (pendingRemoval && !a.startsWith("Error:")) {
            return;
        }
        synchronized (outQueue) {
            outQueue.add(a);
        }
    }

    /**
     * セッション終了処理。
     * - 入出力スレッドの停止を待機し、ソケットを閉じる。
     */
    public void close() {
        running = false;
        pendingRemoval = true;
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

    /**
     * Tick 処理：入力キューからコマンドを順次処理する。
     * 一定以上のコマンドがある場合は次回以降に延期する。
     */
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

    // --- 内部クラス: InputThread ---
    private class InputThread implements Runnable {
        @Override
        public void run() {
            logger.warning("Starting input thread!");
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

    // --- 内部クラス: OutputThread ---
    private class OutputThread implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    String line;
                    while ((line = outQueue.poll()) != null) {
                        out.write(line);
                        out.write('\n');
                    }
                    out.flush();
                    Thread.yield();
                    Thread.sleep(2L);
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
}
