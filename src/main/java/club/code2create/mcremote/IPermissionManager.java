package club.code2create.mcremote;

import org.bukkit.OfflinePlayer;

public interface IPermissionManager {
    boolean canConstructOnline(OfflinePlayer player);
    boolean canConstructOffline(OfflinePlayer player);
    int getPlayerRange(OfflinePlayer player);
}
