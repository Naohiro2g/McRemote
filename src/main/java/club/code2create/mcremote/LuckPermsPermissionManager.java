package club.code2create.mcremote;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class LuckPermsPermissionManager implements IPermissionManager {

    private static final Logger logger = Logger.getLogger("McR_Permission");
    static final String SERVER_CONTEXT_KEY = "server";
    static final String SERVER_CONTEXT_VALUE = "global";

    private LuckPerms luckPerms;
    private final String onlinePermission;
    private final String offlinePermission;
    private final String buildRangeMetaKey;
    private final Supplier<QueryOptions> queryOptionsSupplier;

    /**
     * LuckPermsPermissionManager の初期化はこのコンストラクタ内で行う。
     * LuckPerms のサービスマネージャーからの取得もここで実施するので、
     * McRemotePlugin 側ではLuckPermsの初期化処理を行わない。
     *
     * @param plugin            プラグインインスタンス
     * @param onlinePermission  online 用権限ノード
     * @param offlinePermission offline 用権限ノード
     * @param buildRangeMetaKey effective meta のキー（build.range）
     */
    public LuckPermsPermissionManager(JavaPlugin plugin, String onlinePermission, String offlinePermission, String buildRangeMetaKey) {
        this.onlinePermission = onlinePermission;
        this.offlinePermission = offlinePermission;
        this.buildRangeMetaKey = buildRangeMetaKey;
        this.queryOptionsSupplier = LuckPermsPermissionManager::serverGlobalQueryOptions;
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

    LuckPermsPermissionManager(
            LuckPerms luckPerms,
            String onlinePermission,
            String offlinePermission,
            String buildRangeMetaKey,
            Supplier<QueryOptions> queryOptionsSupplier) {
        this.luckPerms = luckPerms;
        this.onlinePermission = onlinePermission;
        this.offlinePermission = offlinePermission;
        this.buildRangeMetaKey = buildRangeMetaKey;
        this.queryOptionsSupplier = queryOptionsSupplier;
    }

    @Override
    public ConstructionPermissions resolveConstructionPermissions(OfflinePlayer player) {
        UUID uuid = player.getUniqueId();
        User user = luckPerms.getUserManager().loadUser(uuid).join();
        if (user == null) {
            logger.warning("LuckPerms: User not found for " + uuid);
            return new ConstructionPermissions(false, false, 0);
        }
        QueryOptions options = queryOptionsSupplier.get();
        Tristate online = user.getCachedData().getPermissionData(options).checkPermission(onlinePermission);
        Tristate offline = user.getCachedData().getPermissionData(options).checkPermission(offlinePermission);
        String metaValue = user.getCachedData().getMetaData(options).getMetaValue(buildRangeMetaKey);
        int range = 0;
        if (metaValue != null) {
            try {
                range = Math.max(0, Integer.parseInt(metaValue));
            } catch (NumberFormatException e) {
                logger.warning("LuckPerms: Invalid effective meta value '" + metaValue
                        + "' for key '" + buildRangeMetaKey + "' on user " + uuid + ".");
            }
        } else {
            logger.info("LuckPerms: User " + uuid + " does not have effective meta '"
                    + buildRangeMetaKey + "'.");
        }
        logger.info("LuckPerms: construction snapshot on " + uuid
                + " online=" + online + " offline=" + offline + " range=" + range);
        return new ConstructionPermissions(online.asBoolean(), offline.asBoolean(), range);
    }

    private static QueryOptions serverGlobalQueryOptions() {
        return QueryOptions.contextual(serverGlobalContext());
    }

    private static ImmutableContextSet serverGlobalContext() {
        return ImmutableContextSet.builder().add(SERVER_CONTEXT_KEY, SERVER_CONTEXT_VALUE).build();
    }
}
