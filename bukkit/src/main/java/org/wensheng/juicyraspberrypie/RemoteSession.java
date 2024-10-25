package org.wensheng.juicyraspberrypie;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;

public class RemoteSession {
    private static final Logger logger = Logger.getLogger("MCR_RemoteSession");
    boolean pendingRemoval = false;
    private Location origin = null;
    private Player attachedPlayer = null;
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private Thread inThread;
    private Thread outThread;
    private ArrayDeque<String> inQueue = new ArrayDeque<>();
    private final ArrayDeque<String> outQueue = new ArrayDeque<>();
    private boolean running = true;
    private JuicyRaspberryPie plugin;
    private ArrayDeque<PlayerInteractEvent> interactEventQueue = new ArrayDeque<>();
    private ArrayDeque<AsyncPlayerChatEvent> chatPostedQueue = new ArrayDeque<>();
    private ArrayDeque<ProjectileHitEvent> projectileHitQueue = new ArrayDeque<>();
    private int maxCommandsPerTick = 9000; // Maximum number of commands to process per tick

    private PlayerCommands playerCommands;
    private BlockCommands blockCommands;
    private MiscCommands miscCommands;
    private EntityCommands entityCommands;

    RemoteSession(JuicyRaspberryPie plugin, Socket socket) throws IOException {
        this.plugin = plugin;
        this.socket = socket;
        this.playerCommands = new PlayerCommands(this);
        this.miscCommands = new MiscCommands(this);
        this.entityCommands = new EntityCommands(this);
        this.blockCommands = new BlockCommands(this, miscCommands);
        init();
    }

    /**
     * Initializes the session.
     */
    private void init() throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setTrafficClass(0x10);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        startThreads();
    }

    /**
     * Starts the input and output threads.
     */
    private void startThreads() {
        inThread = new Thread(new InputThread());
        inThread.start();
        outThread = new Thread(new OutputThread());
        outThread.start();
        logger.info("Started input and output threads.");
    }

    /**
     * Sets the default origin.
     */
    private void setDefaultOrigin() {
        World defaultWorld = Bukkit.getWorlds().get(0);
        if (defaultWorld != null) {
            origin = defaultWorld.getSpawnLocation();
            logger.info("Default origin set to: " + origin);
        } else {
            logger.warning("No default world found.");
        }
    }

    public void setOrigin(Location origin) {
        this.origin = origin;
        logger.info("Origin set to: " + origin);
    }

    public Location getOrigin() {
        return this.origin;
    }

    /**
     * Handles incoming commands and executes the appropriate actions.
     *
     * @param c The command string to handle.
     * @param args The arguments for the command.
     */
    private void handleCommand(String c, String[] args) {
        try {
            if (this.origin == null && !c.equals("setPlayer")) {
                send("Error: Player and its origin are not set, please use setPlayer(player_name, x, y, z)");
                logger.warning("Player and its origin are not set, please use setPlayer(player_name, x, y, z)");
                close();
                return;
            }

            switch (c) {
                case "world.getBlock":
                case "world.getBlocks":
                case "world.getBlockWithData":
                case "world.setBlock":
                case "world.setBlocks":
                    blockCommands.handleCommand(c, args);
                    break;
                case "world.getNearbyEntities":
                    miscCommands.handleGetNearbyEntities(origin.getWorld(), args);
                    break;
                case "world.getHeight":
                    miscCommands.handleGetHeight(origin.getWorld(), args);
                    break;
                case "chat.post":
                    miscCommands.handleChatPost(args);
                    break;
                case "entity.getPos":
                case "entity.setPos":
                case "entity.getRotation":
                case "entity.setRotation":
                case "entity.getPitch":
                case "entity.setPitch":
                case "entity.getYaw":
                case "entity.setYaw":
                case "entity.remove":
                    entityCommands.handleEntityCommand(c, args);
                    break;
                case "setPlayer":
                    playerCommands.handleSetPlayerCommand(args);
                    break;
                default:
                    send("Error: No such entity/player command" + c);
                    logger.warning("No such entity/player command: " + c);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            close();
        }
    }

    public Socket getSocket() {
        return socket;
    }

    public JuicyRaspberryPie getPlugin() {
        return plugin;
    }

    public void send(Object a) {
        send(a.toString());
    }

    public void send(String a) {
        if (pendingRemoval) return;
        synchronized(outQueue) {
            outQueue.add(a);
        }
    }

    public void close() {
        running = false;
        pendingRemoval = true;
         //wait for threads to stop
        try {
            inThread.join(2000);
            outThread.join(2000);
        }
        catch (InterruptedException e) {
            plugin.logger.warning("Failed to stop in/out thread");
            e.printStackTrace();
        }

        try {
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        plugin.logger.info("Closed connection to" + socket.getRemoteSocketAddress() + ".");
    }

    public void handlePlayerQuitEvent() {
        if (attachedPlayer != null) {
            logger.info("Player " + attachedPlayer.getName() + " has quit.");
            attachedPlayer = null;
        }
    }

    public void kick(String reason) {
        if (attachedPlayer != null) {
            attachedPlayer.kickPlayer(reason);
            logger.info("Player " + attachedPlayer.getName() + " was kicked for: " + reason);
            attachedPlayer = null;
        }
    }

    private Location parseRelativeBlockLocation(String x, String y, String z) {
        return new Location(
            this.origin.getWorld(),
            this.origin.getX() + Double.parseDouble(x),
            this.origin.getY() + Double.parseDouble(y),
            this.origin.getZ() + Double.parseDouble(z)
        );
    }

    void queueProjectileHitEvent(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();

        if (projectile.getShooter() instanceof Player) {
            Player shooter = (Player) projectile.getShooter();
            projectileHitQueue.add(event);
        }
    }

    void queuePlayerInteractEvent(PlayerInteractEvent event) {
        interactEventQueue.add(event);
    }

    void queueChatPostedEvent(AsyncPlayerChatEvent event) {
        chatPostedQueue.add(event);
    }

    void tick() {
        int processedCount = 0;
        String message;
        while ((message = inQueue.poll()) != null) {
            handleLine(message);
            processedCount++;
            if (processedCount >= maxCommandsPerTick) {
                plugin.logger.warning("Over " + maxCommandsPerTick +
                    " commands were queued - deferring " + inQueue.size() + " to next tick");
                break;
            }
        }

        if (!running && inQueue.size() <= 0) {
            pendingRemoval = true;
        }
    }

    private void handleLine(String line) {
        String[] parts = line.split("\\(", 2);
        String command = parts[0];
        String[] args = parts.length > 1 ? parts[1].substring(0, parts[1].length() - 1).split(",") : new String[0];
        handleCommand(command, args);
    }

    private class InputThread implements Runnable {
        @Override
        public void run() {
            plugin.logger.info("Starting input thread!");
            while (running) {
                try {
                    String newLine = in.readLine();
                    if (newLine == null) {
                        running = false;
                    } else {
                        inQueue.add(newLine);
                    }
                } catch (Exception e) {
                    if (running) {
                        e.printStackTrace();
                        running = false;
                        // close();
                    }
                }
            }
            try {
                in.close();
                plugin.logger.warning("Closing input buffer");
            } catch (Exception e) {
                plugin.logger.warning("Failed to close input buffer");
                e.printStackTrace();
            }
        }
    }

    private class OutputThread implements Runnable {
        @Override
        public void run() {
            plugin.logger.info("Starting output thread!");
            while (running) {
                try {
                    String line;
                    while ((line = outQueue.poll()) != null) {
                        out.write(line);
                        out.write('\n');
                    }
                    out.flush();
                    Thread.yield();
                    Thread.sleep(1L);
                } catch (Exception e) {
                    if (running) {
                        e.printStackTrace();
                        running = false;
                        // close();
                    }
                }
            }
            try {
                out.close();
                plugin.logger.warning("Closing output buffer");
            } catch (Exception e) {
                plugin.logger.warning("Failed to close output buffer");
                e.printStackTrace();
            }
        }
    }
}
