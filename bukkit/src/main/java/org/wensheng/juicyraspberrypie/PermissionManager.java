package org.wensheng.juicyraspberrypie;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.OfflinePlayer;

public class PermissionManager {
    private static final LuckPerms luckPerms;

    static {
        LuckPerms tempLuckPerms;
        try {
            tempLuckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            tempLuckPerms = null;
        }
        luckPerms = tempLuckPerms;
    }

    public static boolean isLuckPermsAvailable() {
        return luckPerms != null;
    }

    private static boolean hasPermission(OfflinePlayer offlinePlayer, String permission) {
        if (luckPerms == null) {return true;}
        User user = luckPerms.getUserManager().getUser(offlinePlayer.getUniqueId());
        if (user == null) {return false;}
        return user.getNodes().contains(Node.builder(permission).build());
    }

    public static boolean canConstructOnline(OfflinePlayer offlinePlayer) {
        return hasPermission(offlinePlayer, "minecraft_remote.construction.online");
    }

    public static boolean canConstructOffline(OfflinePlayer offlinePlayer) {
        return hasPermission(offlinePlayer, "minecraft_remote.construction.offline");
    }
}