package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.logging.Logger;

public class McRemote extends JavaPlugin implements Listener{
    final Logger logger = Logger.getLogger("McR");
    private static final Set<Material> blockBreakDetectionTools = EnumSet.of(
            Material.DIAMOND_SWORD,
            Material.GOLDEN_SWORD,
            Material.IRON_SWORD,
            Material.STONE_SWORD,
            Material.WOODEN_SWORD);

    private ServerListenerThread serverThread;
    private final List<RemoteSession> sessions = new ArrayList<>();
    private static boolean luckPermsEnabled;

    private void save_resources(){
        File py_init_file = new File(getDataFolder(), "config.yml");
        if(!py_init_file.exists()){
            this.saveResource("config.yml", false);
        }

        File mcpiFolder = new File(getDataFolder(), "mc_remote");
        if(!mcpiFolder.exists()) {
            boolean ok = mcpiFolder.mkdir();
            if (ok) {
                this.saveResource("mc_remote/connection.py", false);
                this.saveResource("mc_remote/event.py", false);
                this.saveResource("mc_remote/minecraft.py", false);
                this.saveResource("mc_remote/util.py", false);
                this.saveResource("mc_remote/vec3.py", false);
            } else {
                logger.warning("Could not create mcpi directory in plugin.");
            }
        }
    }

    public static boolean isLuckPermsEnabled() {
        return luckPermsEnabled;
    }
    public void onEnable(){
        this.saveDefaultConfig();
        int port = this.getConfig().getInt("api_port");

        //create new tcp listener thread
        try {
            serverThread = new ServerListenerThread(this, new InetSocketAddress(port));
            new Thread(serverThread).start();
            logger.info("ThreadListener Started");
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
            logger.warning("Failed to start ThreadListener");
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new TickHandler(), 1, 1);

        this.save_resources();

        // check LuckPerms availability
        luckPermsEnabled = (Bukkit.getPluginManager().getPlugin("LuckPerms") != null);
        if (luckPermsEnabled) {
            PermissionManager.init(this);
        }
        getLogger().warning("LuckPerms enabled: " + luckPermsEnabled);
    }

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

    @NullMarked

    private class TickHandler implements Runnable {
        public void run() {
            Iterator<RemoteSession> sI = sessions.iterator();
            while(sI.hasNext()) {
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

        //ItemStack currentTool = event.getPlayer().getInventory().getItemInMainHand();
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

    /** called when a new session is established. */
    void handleConnection(RemoteSession newSession) {
        if (checkBanned(newSession)) {
            logger.warning("Kicking " + newSession.getSocket().getRemoteSocketAddress() + " because the IP address has been banned.");
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

}
