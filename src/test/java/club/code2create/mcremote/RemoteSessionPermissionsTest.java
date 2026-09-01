package club.code2create.mcremote;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSessionPermissionsTest {
    @Test
    void helloPermissionsUseOneImmutableSnapshot() {
        ConstructionPermissions snapshot = new ConstructionPermissions(true, false, 500);
        Map<String, Object> result = RemoteSession.buildPermissions(snapshot);
        assertEquals(List.of("online", "offline", "buildRange"), List.copyOf(result.keySet()));
        assertEquals(true, result.get("online"));
        assertEquals(false, result.get("offline"));
        assertEquals(500, result.get("buildRange"));
    }

    @Test
    void fallbackPermissionManagerResolvesBothNodesAndRangeTogether() {
        FallbackPermissionManager manager = new FallbackPermissionManager(
                "mcr.online", "mcr.offline", 32);
        assertEquals(new ConstructionPermissions(true, true, 32),
                manager.resolveConstructionPermissions(player()));
    }

    @Test
    void onlineAndOfflineNodesAreIndependentForHelloAdmission() {
        ConstructionPermissions none = new ConstructionPermissions(false, false, 10);
        ConstructionPermissions onlineOnly = new ConstructionPermissions(true, false, 10);
        ConstructionPermissions offlineOnly = new ConstructionPermissions(false, true, 10);
        ConstructionPermissions both = new ConstructionPermissions(true, true, 10);
        assertFalse(none.allows(true));
        assertFalse(none.allows(false));
        assertTrue(onlineOnly.allows(true));
        assertFalse(onlineOnly.allows(false));
        assertFalse(offlineOnly.allows(true));
        assertTrue(offlineOnly.allows(false));
        assertTrue(both.allows(true));
        assertTrue(both.allows(false));
        assertFalse(RemoteSession.helloConstructionAllowed(true, none, true));
        assertFalse(RemoteSession.helloConstructionAllowed(true, none, false));
        assertTrue(RemoteSession.helloConstructionAllowed(true, onlineOnly, true));
        assertFalse(RemoteSession.helloConstructionAllowed(true, onlineOnly, false));
        assertFalse(RemoteSession.helloConstructionAllowed(true, offlineOnly, true));
        assertTrue(RemoteSession.helloConstructionAllowed(true, offlineOnly, false));
        assertTrue(RemoteSession.helloConstructionAllowed(true, both, true));
        assertTrue(RemoteSession.helloConstructionAllowed(true, both, false));
        assertTrue(RemoteSession.helloConstructionAllowed(false, none, true));
    }

    @Test
    void sessionLifecycleClosesOnlyWhenItsSingleAllowedStateEnds() {
        ConstructionPermissions onlineOnly = new ConstructionPermissions(true, false, 10);
        ConstructionPermissions offlineOnly = new ConstructionPermissions(false, true, 10);
        ConstructionPermissions both = new ConstructionPermissions(true, true, 10);
        assertTrue(onlineOnly.closesOnQuit());
        assertFalse(onlineOnly.closesOnJoin());
        assertFalse(offlineOnly.closesOnQuit());
        assertTrue(offlineOnly.closesOnJoin());
        assertFalse(both.closesOnQuit());
        assertFalse(both.closesOnJoin());
    }

    @Test
    void permissionAndRangeChangesRequireANewSnapshot() {
        MutablePermissionProvider provider = new MutablePermissionProvider();
        ConstructionPermissions existing = provider.resolveConstructionPermissions(player());
        provider.online = false;
        provider.offline = true;
        provider.range = 900;
        assertEquals(new ConstructionPermissions(true, false, 100), existing);
        assertEquals(new ConstructionPermissions(false, true, 900),
                provider.resolveConstructionPermissions(player()));
        assertEquals(2, provider.resolutions);
    }

    @Test
    void negativeBuildRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionPermissions(true, true, -1));
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
        UUID uuid = UUID.randomUUID();
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "TestPlayer";
                    case "toString" -> "OfflinePlayerTestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static final class MutablePermissionProvider implements IPermissionManager {
        private boolean online = true;
        private boolean offline;
        private int range = 100;
        private int resolutions;

        @Override
        public ConstructionPermissions resolveConstructionPermissions(OfflinePlayer player) {
            resolutions++;
            return new ConstructionPermissions(online, offline, range);
        }
    }
}
