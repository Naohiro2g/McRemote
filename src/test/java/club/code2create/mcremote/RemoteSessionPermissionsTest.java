package club.code2create.mcremote;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        OfflinePlayer player = player();
        FallbackPermissionManager permissions = new FallbackPermissionManager(
                "mcr.online", "mcr.offline", 32);

        assertTrue(permissions.canConstructOnline(player));
        assertTrue(permissions.canConstructOffline(player));
        assertEquals(32, permissions.getPlayerRange(player));
    }

    private static OfflinePlayer player() {
        UUID uuid = UUID.randomUUID();
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "TestPlayer";
                    case "toString" -> "OfflinePlayerTestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
