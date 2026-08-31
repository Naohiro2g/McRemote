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
    private final String lightningPermission;
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
        this(plugin, onlinePermission, offlinePermission, "mcr.lightning", buildRangeMetaKey);
    }

    public LuckPermsPermissionManager(
            JavaPlugin plugin,
            String onlinePermission,
            String offlinePermission,
            String lightningPermission,
            String buildRangeMetaKey
    ) {
        this.onlinePermission = onlinePermission;
        this.offlinePermission = offlinePermission;
        this.lightningPermission = lightningPermission;
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
        this(luckPerms, onlinePermission, offlinePermission, "mcr.lightning",
                buildRangeMetaKey, queryOptionsSupplier);
    }

    LuckPermsPermissionManager(
            LuckPerms luckPerms,
            String onlinePermission,
            String offlinePermission,
            String lightningPermission,
            String buildRangeMetaKey,
            Supplier<QueryOptions> queryOptionsSupplier) {
        this.luckPerms = luckPerms;
        this.onlinePermission = onlinePermission;
        this.offlinePermission = offlinePermission;
        this.lightningPermission = lightningPermission;
        this.buildRangeMetaKey = buildRangeMetaKey;
        this.queryOptionsSupplier = queryOptionsSupplier;
    }

    @Override
    public boolean canConstructOnline(OfflinePlayer player) {
        return hasPermission(player, onlinePermission);
    }

    @Override
    public boolean canConstructOffline(OfflinePlayer player) {
        return hasPermission(player, offlinePermission);
    }

    @Override
    public boolean canStrikeLightning(OfflinePlayer player) {
        return hasPermission(player, lightningPermission);
    }

    private boolean hasPermission(OfflinePlayer player, String permission) {
        UUID uuid = player.getUniqueId();
        luckPerms.getUserManager().loadUser(uuid).join();
        User user = luckPerms.getUserManager().getUser(uuid);
        if (user == null) {
            logger.warning("LuckPerms: User not found for " + uuid);
            return false;
        }
        QueryOptions options = queryOptionsSupplier.get();
        Tristate result = user.getCachedData().getPermissionData(options).checkPermission(permission);
        logger.info("LuckPerms: Permission check for " + permission + " on " + uuid + " = " + result);
        return result.asBoolean();
    }

    @Override
    public int getPlayerRange(OfflinePlayer player) {
        UUID uuid = player.getUniqueId();
        User user = luckPerms.getUserManager().loadUser(uuid).join();
        if (user == null) {
            logger.warning("LuckPerms: User not found for " + uuid + ". Returning 0.");
            return 0;
        }
        QueryOptions queryOptions = queryOptionsSupplier.get();
        String metaValue = user.getCachedData().getMetaData(queryOptions).getMetaValue(buildRangeMetaKey);
        if (metaValue != null) {
            try {
                return Integer.parseInt(metaValue);
            } catch (NumberFormatException e) {
                logger.warning("LuckPerms: Invalid effective meta value '" + metaValue
                        + "' for key '" + buildRangeMetaKey + "' on user " + uuid + ".");
            }
        } else {
            logger.info("LuckPerms: User " + uuid + " does not have effective meta '"
                    + buildRangeMetaKey + "'.");
        }
        return 0;
    }

    private static QueryOptions serverGlobalQueryOptions() {
        return QueryOptions.contextual(serverGlobalContext());
    }

    private static ImmutableContextSet serverGlobalContext() {
        return ImmutableContextSet.builder().add(SERVER_CONTEXT_KEY, SERVER_CONTEXT_VALUE).build();
    }
}
