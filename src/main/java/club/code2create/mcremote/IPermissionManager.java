package club.code2create.mcremote;

import org.bukkit.OfflinePlayer;

public interface IPermissionManager {
    boolean canConstructOnline(OfflinePlayer player);
    boolean canConstructOffline(OfflinePlayer player);

    /** Dedicated admission for the damage-capable protocol 23.1 lightning command. */
    default boolean canStrikeLightning(OfflinePlayer player) {
        return false;
    }

    int getPlayerRange(OfflinePlayer player);
}
