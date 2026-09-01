package club.code2create.mcremote;

import org.bukkit.OfflinePlayer;

public interface IPermissionManager {
    /** Resolves both independent construction nodes and build range in one hello-time load. */
    ConstructionPermissions resolveConstructionPermissions(OfflinePlayer player);
}
