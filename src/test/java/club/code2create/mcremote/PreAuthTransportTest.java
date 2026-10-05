package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Production session/reader/auth path with local TCP; no running Paper/world mutation. */
class PreAuthTransportTest {
    @Test void authRequiredResponseIsFlushedBeforeTransportCloses() throws Exception {
        PreAuthPolicy policy = new PreAuthPolicy(2, 1, 10, 1024, 4096, 10, 20, 2, 10, 2);
        PreAuthAdmission admission = new PreAuthAdmission(policy);
        McRemote plugin = plugin(policy, admission);
        when(plugin.isAuthEnforcement()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class);
             ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket server = listener.accept()) {
            RemoteSession session = new RemoteSession(plugin, server, admission.acquire());
            try {
                client.setSoTimeout(2000);
                BufferedReader replies = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                send(client, 1, "hello", "{\"protocol\":\"" + ProtocolInfo.PROTOCOL + "\"}");
                String response = awaitReply(session, replies);
                assertTrue(response.contains("auth_required"));
                assertFalse(session.isHelloComplete());
                assertNull(replies.readLine());
            } finally { session.close(); }
        }
    }

    private McRemote plugin(PreAuthPolicy policy, PreAuthAdmission admission) {
        McRemote plugin = mock(McRemote.class);
        when(plugin.preAuthPolicy()).thenReturn(policy);
        when(plugin.preAuthAdmission()).thenReturn(admission);
        when(plugin.getRuntimePolicy()).thenReturn(RuntimePolicy.from(new MemoryConfiguration()));
        when(plugin.getMaxSessionsPerUuid()).thenReturn(16);
        when(plugin.getCatalogService()).thenReturn(mock(CatalogService.class));
        when(plugin.getPairingManager()).thenReturn(new PairingManager(mock(TokenStore.class), 120, 7200, 2));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        return plugin;
    }

    @Test void pairResponsesStayUnchangedThenRateRefusalClosesWithoutIssuingAnotherPair() throws Exception {
        PreAuthPolicy policy = new PreAuthPolicy(2, 1, 10, 1024, 4096, 10, 20, 1, 10, 2);
        PreAuthAdmission admission = new PreAuthAdmission(policy, () -> 0);
        McRemote plugin = plugin(policy, admission);
        try (var bukkit = mockStatic(Bukkit.class);
             ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket server = listener.accept()) {
            bukkit.when(() -> Bukkit.getWorld(any(NamespacedKey.class))).thenReturn(null);
            RemoteSession session = new RemoteSession(plugin, server, admission.acquire());
            client.setSoTimeout(2000);
            BufferedReader replies = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            try {
                send(client, 1, "auth.pairBegin", "{}");
                String response = awaitReply(session, replies);
                assertTrue(response.contains("pair_code"));
                assertTrue(response.contains("expires_in"));
                send(client, 2, "auth.pairBegin", "{}");
                tickUntil(session, () -> session.pendingRemoval);
                assertNull(replies.readLine());
                assertNull(admission.acquire()); // Slot stays held until main-thread removal/close.
                session.close();
                assertNotNull(admission.acquire());
            } finally { session.close(); }
        }
    }

    @Test void successfulHelloReleasesOnlyPreHelloSlotAndCloseDoesNotWaitForIdleReader() throws Exception {
        PreAuthPolicy policy = new PreAuthPolicy(2, 1, 10, 1024, 4096, 10, 20, 2, 10, 2);
        AtomicLong clock = new AtomicLong();
        PreAuthAdmission admission = new PreAuthAdmission(policy, clock::get);
        McRemote plugin = plugin(policy, admission);
        when(plugin.isAuthEnforcement()).thenReturn(true);
        java.util.UUID playerId = java.util.UUID.randomUUID();
        TokenStore tokens = mock(TokenStore.class);
        when(plugin.getTokenStore()).thenReturn(tokens);
        when(tokens.resolve("test-session-token")).thenReturn(new TokenStore.ResolveResult(
                TokenStore.ResolveStatus.ACTIVE,
                new TokenStore.TokenRecord(playerId, TokenStore.TokenType.SESSION,
                        java.time.Instant.now(), java.time.Instant.now().plusSeconds(7200), null, null, null), null));
        when(plugin.getPermissionManager()).thenReturn(new FallbackPermissionManager("online", "offline", 100));
        org.bukkit.OfflinePlayer offline = mock(org.bukkit.OfflinePlayer.class);
        when(offline.getName()).thenReturn("test-player");
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        try (var bukkit = mockStatic(Bukkit.class);
             ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket server = listener.accept()) {
            bukkit.when(() -> Bukkit.getWorld(any(NamespacedKey.class))).thenReturn(world);
            bukkit.when(Bukkit::getMinecraftVersion).thenReturn("1.21.11");
            bukkit.when(() -> Bukkit.getOfflinePlayer(playerId)).thenReturn(offline);
            RemoteSession session = new RemoteSession(plugin, server, admission.acquire());
            try {
                assertNull(admission.acquire());
                send(client, 1, "hello", "{\"protocol\":\"" + ProtocolInfo.PROTOCOL
                        + "\",\"auth\":{\"token\":\"test-session-token\"}}");
                tickUntil(session, session::isHelloComplete);
                assertEquals(playerId, session.getBoundUuid());
                var second = admission.acquire();
                assertNotNull(second);
                second.authenticated();
                assertNull(admission.acquire()); // Total includes both hello-complete sessions.
                long start = System.nanoTime();
                session.close();
                assertTrue(System.nanoTime() - start < 500_000_000L);
                assertNotNull(admission.acquire());
                second.close();
            } finally { session.close(); }
        }
    }

    private static void send(Socket client, int id, String method, String params) throws Exception {
        client.getOutputStream().write(("{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"" + method + "\",\"params\":" + params + "}\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String awaitReply(RemoteSession session, BufferedReader replies) throws Exception {
        tickUntil(session, replies::ready);
        return replies.readLine();
    }

    private static void tickUntil(RemoteSession session, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!condition.check() && System.nanoTime() < deadline) { session.tick(); Thread.sleep(5); }
        assertTrue(condition.check(), "session did not reach expected state");
    }
    private interface CheckedCondition { boolean check() throws Exception; }
}
