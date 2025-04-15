package club.code2create.mcremote;

import org.bukkit.OfflinePlayer;

import java.util.logging.Logger;

public class FallbackPermissionManager implements IPermissionManager {

    private static final Logger logger = Logger.getLogger("McR_Permission");

    private final String onlinePermission;
    private final String offlinePermission;
    private final int defaultBuildRange;

    public FallbackPermissionManager(String onlinePermission, String offlinePermission, int defaultBuildRange) {
        this.onlinePermission = onlinePermission;
        this.offlinePermission = offlinePermission;
        this.defaultBuildRange = defaultBuildRange;
        logger.info("FallbackPermissionManager initialized with defaultBuildRange: " + defaultBuildRange);
    }

    @Override
    public boolean canConstructOnline(OfflinePlayer player) {
        logger.info("Fallback: always allowing permission '" + onlinePermission + "' for " + player.getName());
        return true;
    }

    @Override
    public boolean canConstructOffline(OfflinePlayer player) {
        logger.info("Fallback: always allowing permission '" + offlinePermission + "' for " + player.getName());
        return true;
    }

    @Override
    public int getPlayerRange(OfflinePlayer player) {
//        logger.info("Fallback: returning default build range " + defaultBuildRange + " for " + player.getName());
        return defaultBuildRange;
    }
}
