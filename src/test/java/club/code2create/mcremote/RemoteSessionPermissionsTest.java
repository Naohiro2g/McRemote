package club.code2create.mcremote;

import org.bukkit.OfflinePlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteSessionPermissionsTest {
    @Test
    void helloPermissionsAreDeterministicAndUseProviderBuildRange() {
        OfflinePlayer player = player();
        IPermissionManager permissions = new IPermissionManager() {
            @Override
            public boolean canConstructOnline(OfflinePlayer actual) {
                assertEquals(player, actual);
                return true;
            }

            @Override
            public boolean canConstructOffline(OfflinePlayer actual) {
                assertEquals(player, actual);
                return false;
            }

            @Override
            public int getPlayerRange(OfflinePlayer actual) {
                assertEquals(player, actual);
                return 500;
            }
        };

        Map<String, Object> result = RemoteSession.buildPermissions(permissions, player);

        assertEquals(List.of("online", "offline", "buildRange"), List.copyOf(result.keySet()));
        assertEquals(true, result.get("online"));
        assertEquals(false, result.get("offline"));
        assertEquals(500, result.get("buildRange"));
    }

    @Test
    void fallbackPermissionManagerKeepsExistingDefaults() {
        OfflinePlayer player = player(false);
        FallbackPermissionManager permissions = new FallbackPermissionManager(
                "mcr.online", "mcr.offline", 32);

        assertTrue(permissions.canConstructOnline(player));
        assertTrue(permissions.canConstructOffline(player));
        assertFalse(permissions.canStrikeLightning(player));
        assertTrue(permissions.canStrikeLightning(player(true)));
        assertEquals(32, permissions.getPlayerRange(player));
    }

    @Test
    void constructionPermissionSelectsOnlineOrOfflineNodeFromCurrentPlayerState() {
        AtomicInteger onlineChecks = new AtomicInteger();
        AtomicInteger offlineChecks = new AtomicInteger();
        IPermissionManager permissions = new IPermissionManager() {
            @Override public boolean canConstructOnline(OfflinePlayer player) {
                onlineChecks.incrementAndGet();
                return true;
            }
            @Override public boolean canConstructOffline(OfflinePlayer player) {
                offlineChecks.incrementAndGet();
                return false;
            }
            @Override public int getPlayerRange(OfflinePlayer player) { return 0; }
        };
        OfflinePlayer player = player();
        Player online = onlinePlayer(true);

        assertTrue(RemoteSession.selectConstructionPermission(permissions, player, online));
        assertFalse(RemoteSession.selectConstructionPermission(permissions, player, onlinePlayer(false)));
        assertFalse(RemoteSession.selectConstructionPermission(permissions, player, null));
        assertEquals(1, onlineChecks.get());
        assertEquals(2, offlineChecks.get());
    }

    @Test
    void buildRangeIncludesXZBoundaryAndExcludesY() {
        Location origin = new Location(null, 10.5, 64, -20.5);

        assertTrue(RemoteSession.withinBuildRange(
                origin, new Location(null, 42.5, 1_000_000, 11.5), 32));
        assertTrue(RemoteSession.withinBuildRange(
                origin, new Location(null, -21.5, -1_000_000, -52.5), 32));
        assertFalse(RemoteSession.withinBuildRange(
                origin, new Location(null, 42.500_001, 64, -20.5), 32));
        assertFalse(RemoteSession.withinBuildRange(
                origin, new Location(null, 10.5, 64, 11.500_001), 32));
    }

    private static OfflinePlayer player() {
        return player(false);
    }

    private static OfflinePlayer player(boolean op) {
        UUID uuid = UUID.randomUUID();
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "TestPlayer";
                    case "isOp" -> op;
                    case "toString" -> "OfflinePlayerTestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Player onlinePlayer(boolean online) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> online;
                    case "toString" -> "PlayerPermissionTestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
