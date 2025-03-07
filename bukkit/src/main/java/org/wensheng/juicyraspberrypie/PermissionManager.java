package org.wensheng.juicyraspberrypie;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public class PermissionManager {
    private static final Logger logger = Logger.getLogger("MCR_Permission");
    private static LuckPerms luckPerms;
    private static String PERMISSION_ONLINE = "mcr.online";
    private static String PERMISSION_OFFLINE = "mcr.offline";

    public static String init(JavaPlugin plugin) {
        String msg;
        try {
            // Load permission nodes from config
            FileConfiguration config = plugin.getConfig();
            if (config.contains("permissions.online")) {
                PERMISSION_ONLINE = config.getString("permissions.online");
            }
            if (config.contains("permissions.offline")) {
                PERMISSION_OFFLINE = config.getString("permissions.offline");
            }
            logger.info("Permission settings: online=" + PERMISSION_ONLINE + ", offline=" + PERMISSION_OFFLINE);

            // Initialize LuckPerms
            RegisteredServiceProvider<LuckPerms> provider = plugin.getServer().getServicesManager().getRegistration(LuckPerms.class);
            if (provider != null) {
                luckPerms = provider.getProvider();
                msg = "Successfully connected to LuckPerms";
                return msg;
            } else {
                msg = "LuckPerms provider not found";
                return msg;
            }
        } catch (Exception e) {
            msg = "Failed to connect to LuckPerms: " + e.getMessage();
            return msg;
        }
    }

    public static boolean isLuckPermsAvailable() {
        return luckPerms != null;
    }

    public static boolean canConstructOnline(OfflinePlayer offlinePlayer) {
        return hasPermission(offlinePlayer, PERMISSION_ONLINE);
    }

    public static boolean canConstructOffline(OfflinePlayer offlinePlayer) {
        return hasPermission(offlinePlayer, PERMISSION_OFFLINE);
    }

    private static boolean hasPermission(OfflinePlayer offlinePlayer, String permission) {
        if (luckPerms == null) {
            logger.warning("LuckPerms is not available");
            return true; // Allow by default
        }

        // Load user
        luckPerms.getUserManager().loadUser(offlinePlayer.getUniqueId()).join();
        User user = luckPerms.getUserManager().getUser(offlinePlayer.getUniqueId());
        if (user == null) {
            logger.warning("User not found after loading: " + offlinePlayer.getUniqueId());
            return false;
        }

        // Use global context
        ImmutableContextSet contextSet = ImmutableContextSet.builder()
                .add("server", "global")
                .build();

        QueryOptions options = QueryOptions.contextual(contextSet);

        logger.info("Using global context for " + offlinePlayer.getName() +
                " (online: " + offlinePlayer.isOnline() + ")");

        // Check permission
        Tristate result = user.getCachedData().getPermissionData(options).checkPermission(permission);

        logger.info("Permission check for " + permission + ": " + result);

        return result.asBoolean();
    }
}