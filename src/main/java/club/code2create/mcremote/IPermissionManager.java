package club.code2create.mcremote;

import org.bukkit.OfflinePlayer;

public interface IPermissionManager {
    /** Resolves construction nodes, range and per-operation block count in one hello-time load. */
    ConstructionPermissions resolveConstructionPermissions(OfflinePlayer player);
}
