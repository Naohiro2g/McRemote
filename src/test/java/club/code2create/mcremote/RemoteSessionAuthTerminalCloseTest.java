package club.code2create.mcremote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RemoteSessionAuthTerminalCloseTest {
    private static final Unsafe UNSAFE = loadUnsafe();
    private static Object previousBukkitServer;

    @BeforeAll
    static void installMinimalBukkitServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousBukkitServer = serverField.get(null);
        serverField.set(null, minimalServer());
    }

    @AfterAll
    static void restoreBukkitServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, previousBukkitServer);
    }

    @Test
    void authRequiredFlushesThenClosesBeforePairingContinuesOnNewTcp() throws Exception {
        McRemote plugin = pluginFixture();

        try (SocketHarness rejected = new SocketHarness(plugin)) {
            rejected.send("""
                    {"jsonrpc":"2.0","id":1,"method":"hello","params":{"protocol":"21.0.0"}}
                    """);

            JsonObject error = rejected.readResponse(Duration.ofSeconds(3));
            assertEquals(1, error.get("id").getAsInt());
            assertEquals("auth_required",
                    error.getAsJsonObject("error").getAsJsonObject("data")
                            .get("reason").getAsString());
            assertTrue(rejected.isClosingAfterFlush(),
                    "terminal auth error must use the explicit flush-then-close path");

            // Bridge is payload-transparent and may forward the next browser request before
            // observing FIN. The old session must close promptly and must not process it.
            rejected.trySend("""
                    {"jsonrpc":"2.0","id":2,"method":"auth.pairBegin","params":{"token_type":"session"}}
                    """);
            rejected.assertEofWithin(Duration.ofSeconds(1));
        }

        String pairingId;
        String pairCode;
        try (SocketHarness pairing = new SocketHarness(plugin)) {
            pairing.send("""
                    {"jsonrpc":"2.0","id":2,"method":"auth.pairBegin","params":{"token_type":"session"}}
                    """);
            JsonObject result = pairing.readResponse(Duration.ofSeconds(2))
                    .getAsJsonObject("result");
            pairingId = result.get("pairing_id").getAsString();
            pairCode = result.get("pair_code").getAsString();
            assertTrue(pairCode.matches("[0-9]{6}"));
        }
        assertEquals(1, ((CountingPairingManager) plugin.getPairingManager()).beginCount(),
                "the pairBegin raced onto the terminal TCP must not be processed");

        UUID playerId = UUID.randomUUID();
        assertEquals(PairingManager.BindStatus.OK,
                plugin.getPairingManager().bind(pairCode, playerId));

        String token;
        try (SocketHarness poll = new SocketHarness(plugin)) {
            poll.send("""
                    {"jsonrpc":"2.0","id":3,"method":"auth.pairPoll","params":{"pairing_id":"%s"}}
                    """.formatted(pairingId));
            JsonObject result = poll.readResponse(Duration.ofSeconds(2))
                    .getAsJsonObject("result");
            assertEquals("ok", result.get("status").getAsString());
            token = result.get("token").getAsString();
            assertTrue(token.startsWith("mcrs_"));
        }

        try (SocketHarness authenticated = new SocketHarness(plugin)) {
            authenticated.send("""
                    {"jsonrpc":"2.0","id":4,"method":"hello","params":{"protocol":"21.0.0","auth":{"token":"%s"}}}
                    """.formatted(token));
            JsonObject result = authenticated.readResponse(Duration.ofSeconds(2))
                    .getAsJsonObject("result");
            assertEquals("21.0.0", result.get("protocol").getAsString());
            assertEquals(playerId.toString(), result.get("player").getAsString());
            assertEquals("fixture-catalog-hash", result.get("catalogHash").getAsString());
        }
    }

    @Test
    void terminalHelloErrorsUseFlushThenCloseWhileKeepingTheirReasons() throws Exception {
        assertTerminalError(pluginFixture(), """
                {"jsonrpc":"2.0","id":10,"method":"not.hello","params":{}}
                """, "expected_hello");
        assertTerminalError(pluginFixture(), """
                {"jsonrpc":"2.0","id":11,"method":"hello","params":{}}
                """, "protocol_required");
        assertTerminalError(pluginFixture(), """
                {"jsonrpc":"2.0","id":12,"method":"hello","params":{"protocol":"22.0.0"}}
                """, "protocol_mismatch");

        McRemote denied = pluginFixture(false, 16);
        String deniedToken = denied.getTokenStore().issue(
                UUID.randomUUID(), TokenStore.TokenType.SESSION, null, 7200);
        assertTerminalError(denied, """
                {"jsonrpc":"2.0","id":13,"method":"hello","params":{"protocol":"21.0.0","auth":{"token":"%s"}}}
                """.formatted(deniedToken), "permission_denied");

        McRemote atCapacity = pluginFixture(true, 0);
        String capacityToken = atCapacity.getTokenStore().issue(
                UUID.randomUUID(), TokenStore.TokenType.SESSION, null, 7200);
        assertTerminalError(atCapacity, """
                {"jsonrpc":"2.0","id":14,"method":"hello","params":{"protocol":"21.0.0","auth":{"token":"%s"}}}
                """.formatted(capacityToken), "too_many_sessions");
    }

    private static void assertTerminalError(McRemote plugin, String request, String reason)
            throws Exception {
        try (SocketHarness harness = new SocketHarness(plugin)) {
            harness.send(request);
            JsonObject error = harness.readResponse(Duration.ofSeconds(2));
            assertEquals(reason, error.getAsJsonObject("error").getAsJsonObject("data")
                    .get("reason").getAsString());
            assertTrue(harness.isClosingAfterFlush());
            harness.assertEofWithin(Duration.ofSeconds(1));
        }
    }

    private static McRemote pluginFixture() throws Exception {
        return pluginFixture(true, 16);
    }

    private static McRemote pluginFixture(boolean allowPermissions, int maxSessions)
            throws Exception {
        McRemote plugin = (McRemote) UNSAFE.allocateInstance(McRemote.class);
        TokenStore tokenStore = new TokenStore(null);
        PairingManager pairingManager = new CountingPairingManager(tokenStore);

        putObject(plugin, McRemote.class, "sessions", new CopyOnWriteArrayList<RemoteSession>());
        putObject(plugin, McRemote.class, "tokenStore", tokenStore);
        putObject(plugin, McRemote.class, "pairingManager", pairingManager);
        putObject(plugin, McRemote.class, "credentialService", null);
        putObject(plugin, McRemote.class, "catalogService", catalogFixture());
        putObject(plugin, McRemote.class, "permissionManager", permissions(allowPermissions));
        putBoolean(plugin, McRemote.class, "authEnforcement", true);
        putInt(plugin, McRemote.class, "maxSessionsPerUuid", maxSessions);

        YamlConfiguration config = new YamlConfiguration();
        config.set("supported_mc_versions", List.of("1.21.11"));
        putObject(plugin, JavaPlugin.class, "newConfig", config);
        return plugin;
    }

    private static CatalogService catalogFixture() throws Exception {
        CatalogService catalog = (CatalogService) UNSAFE.allocateInstance(CatalogService.class);
        putObject(catalog, CatalogService.class, "catalogHash", "fixture-catalog-hash");
        return catalog;
    }

    private static IPermissionManager permissions(boolean allowed) {
        return new IPermissionManager() {
            @Override
            public boolean canConstructOnline(OfflinePlayer player) {
                return allowed;
            }

            @Override
            public boolean canConstructOffline(OfflinePlayer player) {
                return allowed;
            }

            @Override
            public int getPlayerRange(OfflinePlayer player) {
                return 1000;
            }
        };
    }

    private static final class CountingPairingManager extends PairingManager {
        private final AtomicInteger begins = new AtomicInteger();

        private CountingPairingManager(TokenStore tokenStore) {
            super(tokenStore, 120, 7200);
        }

        @Override
        public BeginResult begin(TokenStore.TokenType tokenType, String device) {
            begins.incrementAndGet();
            return super.begin(tokenType, device);
        }

        private int beginCount() {
            return begins.get();
        }
    }

    private static Server minimalServer() {
        OfflinePlayer offlinePlayer = (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        return (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorlds" -> List.of();
                    case "getWorld" -> null;
                    case "getOfflinePlayer" -> offlinePlayer;
                    case "getMinecraftVersion" -> "1.21.11";
                    case "getName" -> "fixture";
                    case "getVersion", "getBukkitVersion" -> "fixture-1.21.11";
                    case "getLogger" -> Logger.getLogger("McRemoteSocketFixture");
                    case "toString" -> "MinimalBukkitServer";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        throw new IllegalArgumentException("Unsupported primitive: " + type);
    }

    private static Unsafe loadUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void putObject(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
    }

    private static void putBoolean(Object target, Class<?> owner, String name, boolean value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        UNSAFE.putBoolean(target, UNSAFE.objectFieldOffset(field), value);
    }

    private static void putInt(Object target, Class<?> owner, String name, int value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        UNSAFE.putInt(target, UNSAFE.objectFieldOffset(field), value);
    }

    private static final class SocketHarness implements AutoCloseable {
        private final Socket client;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final RemoteSession session;
        private final AtomicBoolean ticking = new AtomicBoolean(true);
        private final Thread tickThread;

        private SocketHarness(McRemote plugin) throws Exception {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                Socket accepted = listener.accept();
                session = new RemoteSession(plugin, accepted);
            }
            reader = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(
                    client.getOutputStream(), StandardCharsets.UTF_8));
            tickThread = new Thread(() -> {
                while (ticking.get()) {
                    session.tick();
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "mcremote-test-tick");
            tickThread.start();
        }

        private void send(String json) throws IOException {
            writer.write(json.strip());
            writer.write('\n');
            writer.flush();
        }

        private void trySend(String json) {
            try {
                send(json);
            } catch (IOException expectedAfterTerminalResponse) {
                // A fast FIN/RST may make the immediate Bridge-side write fail, which is valid.
            }
        }

        private JsonObject readResponse(Duration timeout) throws IOException {
            client.setSoTimeout(Math.toIntExact(timeout.toMillis()));
            String line = reader.readLine();
            assertNotNull(line, "response must be newline-terminated before EOF");
            return JsonParser.parseString(line).getAsJsonObject();
        }

        private void assertEofWithin(Duration timeout) throws IOException {
            client.setSoTimeout(Math.toIntExact(timeout.toMillis()));
            try {
                assertNull(reader.readLine(), "terminal response must be followed by EOF");
            } catch (SocketTimeoutException e) {
                fail("terminal response was flushed but EOF was not observable within " + timeout);
            }
        }

        private boolean isClosingAfterFlush() throws Exception {
            Field field = RemoteSession.class.getDeclaredField("closingAfterFlush");
            return UNSAFE.getBoolean(session, UNSAFE.objectFieldOffset(field));
        }

        @Override
        public void close() throws Exception {
            ticking.set(false);
            client.close();
            session.close();
            tickThread.join(3000);
            assertFalse(tickThread.isAlive(), "test tick thread must stop");
        }
    }
}
