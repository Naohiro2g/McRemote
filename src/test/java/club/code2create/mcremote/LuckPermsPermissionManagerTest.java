package club.code2create.mcremote;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsPermissionManagerTest {
    private static final String META_KEY = "mcr.build.range";

    @Test
    void returnsEffectiveUserMetaWithoutReadingPrimaryGroup() {
        Fixture fixture = fixture("500");

        assertEquals(500, fixture.manager().getPlayerRange(fixture.player()));
    }

    @Test
    void returnsZeroWhenEffectiveMetaIsMissing() {
        Fixture fixture = fixture(null);

        assertEquals(0, fixture.manager().getPlayerRange(fixture.player()));
    }

    @Test
    void returnsZeroWhenEffectiveMetaIsNotAnInteger() {
        Fixture fixture = fixture("not-an-integer");

        assertEquals(0, fixture.manager().getPlayerRange(fixture.player()));
    }

    @Test
    void keepsServerGlobalQueryContext() {
        assertEquals("server", LuckPermsPermissionManager.SERVER_CONTEXT_KEY);
        assertEquals("global", LuckPermsPermissionManager.SERVER_CONTEXT_VALUE);
    }

    @Test
    void keepsOnlineAndOfflinePermissionChecksOnTheLoadedUser() {
        Fixture fixture = fixture("500");

        assertTrue(fixture.manager().canConstructOnline(fixture.player()));
        assertFalse(fixture.manager().canConstructOffline(fixture.player()));
    }

    private static Fixture fixture(String effectiveMeta) {
        UUID uuid = UUID.randomUUID();
        QueryOptions queryOptions = proxy(QueryOptions.class, (method, args) -> null);
        CachedMetaData metaData = proxy(CachedMetaData.class, (method, args) -> {
            if (method.getName().equals("getMetaValue")) {
                assertEquals(META_KEY, args[0]);
                return effectiveMeta;
            }
            return null;
        });
        CachedPermissionData permissionData = proxy(CachedPermissionData.class, (method, args) -> {
            if (method.getName().equals("checkPermission")) {
                return args[0].equals("mcr.online") ? Tristate.TRUE : Tristate.FALSE;
            }
            return null;
        });
        CachedDataManager cachedData = proxy(CachedDataManager.class, (method, args) -> {
            if (method.getName().equals("getMetaData")) {
                assertSame(queryOptions, args[0]);
                return metaData;
            }
            if (method.getName().equals("getPermissionData")) {
                assertSame(queryOptions, args[0]);
                return permissionData;
            }
            return null;
        });
        User user = proxy(User.class, (method, args) -> switch (method.getName()) {
            case "getCachedData" -> cachedData;
            case "getPrimaryGroup" -> throw new AssertionError("primary group must not be read");
            default -> null;
        });
        UserManager userManager = proxy(UserManager.class, (method, args) -> switch (method.getName()) {
            case "loadUser" -> {
                assertEquals(uuid, args[0]);
                yield CompletableFuture.completedFuture(user);
            }
            case "getUser" -> {
                assertEquals(uuid, args[0]);
                yield user;
            }
            default -> null;
        });
        LuckPerms luckPerms = proxy(LuckPerms.class, (method, args) -> switch (method.getName()) {
            case "getUserManager" -> userManager;
            case "getGroupManager" -> throw new AssertionError("group manager must not be read");
            default -> null;
        });
        OfflinePlayer player = proxy(OfflinePlayer.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> "TestPlayer";
            default -> null;
        });
        LuckPermsPermissionManager manager = new LuckPermsPermissionManager(
                luckPerms, "mcr.online", "mcr.offline", META_KEY, () -> queryOptions);
        return new Fixture(manager, player);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "TestProxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return invocation.invoke(method, args == null ? new Object[0] : args);
                });
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private record Fixture(LuckPermsPermissionManager manager, OfflinePlayer player) {
    }
}
