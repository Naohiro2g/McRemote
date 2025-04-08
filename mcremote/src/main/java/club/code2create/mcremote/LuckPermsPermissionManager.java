package club.code2create.mcremote;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Logger;

public class LuckPermsPermissionManager implements IPermissionManager {

    private static final Logger logger = Logger.getLogger("McR_Permission");

    private LuckPerms luckPerms;
    private final String onlinePermission;
    private final String offlinePermission;
    private final String buildRangeMetaKey;

    /**
     * LuckPermsPermissionManager の初期化はこのコンストラクタ内で行う。
     * LuckPerms のサービスマネージャーからの取得もここで実施するので、
     * McRemotePlugin 側ではLuckPermsの初期化処理を行わない。
     *
     * @param plugin            プラグインインスタンス
     * @param onlinePermission  online 用権限ノード
     * @param offlinePermission offline 用権限ノード
     * @param buildRangeMetaKey グループの meta キー（build.range）
     */
    public LuckPermsPermissionManager(JavaPlugin plugin, String onlinePermission, String offlinePermission, String buildRangeMetaKey) {
        this.onlinePermission = onlinePermission;
        this.offlinePermission = offlinePermission;
        this.buildRangeMetaKey = buildRangeMetaKey;
        try {
            var provider = plugin.getServer().getServicesManager().getRegistration(LuckPerms.class);
            if (provider != null) {
                this.luckPerms = provider.getProvider();
                logger.info("Successfully connected to LuckPerms.");
            } else {
                logger.severe("LuckPerms provider not found in LuckPermsPermissionManager.");
            }
        } catch (Exception e) {
            logger.severe("Exception initializing LuckPermsPermissionManager: " + e.getMessage());
        }
    }

    @Override
    public boolean canConstructOnline(OfflinePlayer player) {
        return hasPermission(player, onlinePermission);
    }

    @Override
    public boolean canConstructOffline(OfflinePlayer player) {
        return hasPermission(player, offlinePermission);
    }

    private boolean hasPermission(OfflinePlayer player, String permission) {
        UUID uuid = player.getUniqueId();
        luckPerms.getUserManager().loadUser(uuid).join();
        User user = luckPerms.getUserManager().getUser(uuid);
        if (user == null) {
            logger.warning("LuckPerms: User not found for " + uuid);
            return false;
        }
        ImmutableContextSet contextSet = ImmutableContextSet.builder().add("server", "global").build();
        QueryOptions options = QueryOptions.contextual(contextSet);
        Tristate result = user.getCachedData().getPermissionData(options).checkPermission(permission);
        logger.info("LuckPerms: Permission check for " + permission + " on " + uuid + " = " + result);
        return result.asBoolean();
    }

    @Override
    public int getPlayerRange(OfflinePlayer player) {
        UUID uuid = player.getUniqueId();
        luckPerms.getUserManager().loadUser(uuid).join();
        User user = luckPerms.getUserManager().getUser(uuid);
        if (user == null) {
            logger.warning("LuckPerms: User not found for " + uuid + ". Returning 0.");
            return 0;
        }
        String primaryGroup = user.getPrimaryGroup();
        if (primaryGroup.isEmpty()) {
            logger.warning("LuckPerms: Primary group not found for user " + player.getName() + ". Returning 0.");
            return 0;
        }
        Group group = luckPerms.getGroupManager().getGroup(primaryGroup);
        if (group == null) {
            logger.warning("LuckPerms: Group '" + primaryGroup + "' not found. Returning 0.");
            return 0;
        }
        ImmutableContextSet contextSet = ImmutableContextSet.builder().add("server", "global").build();
        QueryOptions queryOptions = QueryOptions.contextual(contextSet);
        String metaValue = group.getCachedData().getMetaData(queryOptions).getMetaValue(buildRangeMetaKey);
        if (metaValue != null) {
            try {
                int range = Integer.parseInt(metaValue);
//                logger.info("LuckPerms: Retrieved build.range from group '" + primaryGroup + "': " + range);
                return range;
            } catch (NumberFormatException e) {
                logger.warning("LuckPerms: Invalid build.range meta value '" + metaValue + "' for group '" + primaryGroup + "'.");
            }
        } else {
            logger.info("LuckPerms: Group '" + primaryGroup + "' does not have meta '" + buildRangeMetaKey + "'.");
        }
        return 0;
    }
}
