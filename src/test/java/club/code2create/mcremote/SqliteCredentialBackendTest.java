package club.code2create.mcremote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** SQLiteでもfile backendと同じcredential契約を満たすことを確認する。 */
class SqliteCredentialBackendTest {
    @TempDir Path temp;

    @Test
    void everyBackendConnectionReadsBackWalAndFullAndRejectsChangedJournalMode() throws Exception {
        Paths paths = paths("pragmas");
        service(paths);
        for (var entry : List.of(new Object[]{paths.snapshot(), "snapshot"},
                new Object[]{paths.authority(), "authority"})) {
            var database = new SqliteCredentialDatabase((Path) entry[0], (String) entry[1]);
            database.read(connection -> {
                try (var statement = connection.createStatement()) {
                    try (var result = statement.executeQuery("SELECT sqlite_version()")) {
                        assertTrue(result.next());
                        assertEquals("3.53.4", result.getString(1), "must use the fixed bundled engine");
                    }
                    try (var result = statement.executeQuery("PRAGMA journal_mode")) {
                        assertTrue(result.next());
                        assertEquals("wal", result.getString(1));
                    }
                    try (var result = statement.executeQuery("PRAGMA synchronous")) {
                        assertTrue(result.next());
                        assertEquals(2, result.getInt(1));
                    }
                }
                return null;
            });
        }
        sql(paths.authority(), "PRAGMA journal_mode=DELETE");
        assertEquals(CredentialService.Health.UNHEALTHY, service(paths).health());
    }

    @Test
    void lifecycleSurvivesRestartAndStoresOnlyHashesInJapaneseAndSpacePath() throws Exception {
        Paths paths = paths("教室 PC 保存先");
        CredentialService service = service(paths);
        assertEquals(CredentialService.Health.HEALTHY, service.health());
        UUID player = UUID.randomUUID();
        var longLived = service.issue(player, "講師 PC");
        var session = service.issueSession(player, null, 7200);
        var touched = service.resolveAndTouch(longLived.token());
        assertEquals(CredentialService.ResolveStatus.ACTIVE, touched.status());
        assertNotNull(touched.record().lastUsedAt());
        CredentialService restarted = service(paths);
        assertEquals(CredentialService.ResolveStatus.ACTIVE,
                restarted.resolveAndTouch(session.token()).status());
        assertEquals(player, restarted.resolveAndTouch(longLived.token()).record().playerUuid());
        assertTrue(restarted.revoke(player, longLived.credentialId()).projectionUpdated());
        CredentialService afterRevoke = service(paths);
        assertEquals(CredentialService.ResolveStatus.REVOKED,
                afterRevoke.resolveAndTouch(longLived.token()).status());
        assertTrue(afterRevoke.list(player).isEmpty());
        try (var walk = Files.walk(paths.snapshot().getParent().getParent())) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String bytes = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains(longLived.token()), "raw long-lived token persisted");
                assertFalse(bytes.contains(session.token()), "raw session token persisted");
            }
        }
    }

    @Test
    void snapshotOnlyRollbackCannotUndoAuthorityRevokeOrConsumeActiveLimit() throws Exception {
        Paths paths = paths("rollback");
        CredentialService original = service(paths, 1);
        UUID player = UUID.randomUUID();
        var issued = original.issue(player, "first");
        assertFalse(Files.exists(Path.of(paths.snapshot() + "-wal")), "snapshot backup requires a closed bundle");
        byte[] oldSnapshot = Files.readAllBytes(paths.snapshot());
        original.revoke(player, issued.credentialId());
        Files.write(paths.snapshot(), oldSnapshot);
        CredentialService restarted = service(paths, 1);
        assertEquals(CredentialService.Health.HEALTHY, restarted.health());
        assertEquals(CredentialService.ResolveStatus.REVOKED,
                restarted.resolveAndTouch(issued.token()).status());
        assertTrue(restarted.list(player).isEmpty());
        assertNotNull(restarted.issue(player, "replacement"));
    }

    @Test
    void sessionDoesNotUseLongLivedLimitAndExpiredSessionRemainsExpired() throws Exception {
        Paths paths = paths("sessions");
        CredentialService service = service(paths, 1);
        UUID player = UUID.randomUUID();
        var session = service.issueSession(player, null, 7200);
        service.issueSession(player, null, 7200);
        var credential = service.issue(player, "managed");
        assertEquals(1, service.list(player).size());
        assertThrows(CredentialLimitReachedException.class, () -> service.issue(player, "over limit"));
        assertThrows(CredentialService.CredentialNotFoundException.class,
                () -> service.revoke(player, session.credentialId()));
        var snapshot = new SqliteCredentialStore(paths.snapshot()).load();
        List<CredentialStore.CredentialRecord> records = snapshot.records().stream()
                .map(record -> record.credentialId().equals(session.credentialId())
                        ? new CredentialStore.CredentialRecord(record.credentialId(), record.tokenHash(),
                        record.playerUuid(), record.type(), record.device(), record.issuedAt(),
                        record.lastUsedAt(), Instant.parse("2020-01-01T00:00:00Z"), record.revokedAt())
                        : record).toList();
        new SqliteCredentialStore(paths.snapshot()).persist(snapshot.credentialDomainId(), records);
        CredentialService restarted = service(paths);
        assertEquals(CredentialService.ResolveStatus.EXPIRED,
                restarted.resolveAndTouch(session.token()).status());
        assertEquals(CredentialService.ResolveStatus.ACTIVE,
                restarted.resolveAndTouch(credential.token()).status());
    }

    @Test
    void duplicateTombstoneIsIdempotentButContradictionCannotOverwrite() throws Exception {
        Paths paths = paths("duplicate");
        var service = service(paths);
        var authority = new SqliteRevocationAuthority(paths.authority());
        var tombstone = tombstone(service.credentialDomainId());
        assertEquals(tombstone, authority.commit(tombstone));
        long firstMarker = scalar(paths.authority(), "SELECT commit_marker FROM authority_state");
        assertEquals(tombstone, authority.commit(tombstone));
        assertTrue(scalar(paths.authority(), "SELECT commit_marker FROM authority_state") > firstMarker);
        var contradiction = new RevocationAuthority.Tombstone(tombstone.credentialDomainId(),
                tombstone.credentialId(), "different-hash", tombstone.playerUuid(), tombstone.revokedAt());
        assertThrows(IOException.class, () -> authority.commit(contradiction));
        assertEquals(List.of(tombstone), authority.loadTombstones(tombstone.credentialDomainId()));
    }

    @Test
    void concurrentSameIdContradictionHasExactlyOneWinnerAndKeepsItsContents() throws Exception {
        Paths paths = paths("parallel");
        UUID domain = service(paths).credentialDomainId();
        var one = tombstone(domain);
        var two = new RevocationAuthority.Tombstone(domain, one.credentialId(), "different",
                one.playerUuid(), one.revokedAt());
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RevocationAuthority.Tombstone>> futures = new ArrayList<>();
            for (var value : List.of(one, two)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return new SqliteRevocationAuthority(paths.authority()).commit(value);
                }));
            }
            start.countDown();
            int successes = 0, failures = 0;
            RevocationAuthority.Tombstone winner = null;
            for (var future : futures) {
                try { winner = future.get(15, TimeUnit.SECONDS); successes++; }
                catch (ExecutionException e) { assertInstanceOf(IOException.class, e.getCause()); failures++; }
            }
            assertEquals(1, successes);
            assertEquals(1, failures);
            assertEquals(List.of(winner), new SqliteRevocationAuthority(paths.authority()).loadTombstones(domain));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void concurrentIdenticalRetryBothCommitAndIncreaseMarker() throws Exception {
        Paths paths = paths("parallel-identical");
        var value = tombstone(service(paths).credentialDomainId());
        long before = scalar(paths.authority(), "SELECT commit_marker FROM authority_state");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RevocationAuthority.Tombstone>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return new SqliteRevocationAuthority(paths.authority()).commit(value);
                }));
            }
            start.countDown();
            for (var future : futures) assertEquals(value, future.get(15, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertEquals(before + 2, scalar(paths.authority(), "SELECT commit_marker FROM authority_state"));
        assertEquals(List.of(value), new SqliteRevocationAuthority(paths.authority())
                .loadTombstones(value.credentialDomainId()));
    }

    @Test
    void snapshotFailureAfterCommitKeepsRevokeSuccessfulAndRestartOverlay() throws Exception {
        Paths paths = paths("projection-error");
        var original = service(paths);
        UUID player = UUID.randomUUID();
        var issued = original.issue(player, "projection");
        byte[] savedSnapshot = Files.readAllBytes(paths.snapshot());
        var observer = new SqliteCredentialDatabase.CommitObserver() {
            @Override public void afterCommit() throws IOException {
                Files.delete(paths.snapshot());
                Files.createDirectory(paths.snapshot());
            }
        };
        var service = new CredentialService(new SqliteCredentialStore(paths.snapshot()),
                new SqliteRevocationAuthority(paths.authority(), observer), 16);
        assertFalse(service.revoke(player, issued.credentialId()).projectionUpdated());
        assertEquals(CredentialService.Health.DEGRADED, service.health());
        Files.delete(paths.snapshot());
        Files.write(paths.snapshot(), savedSnapshot);
        assertEquals(CredentialService.ResolveStatus.REVOKED,
                service(paths).resolveAndTouch(issued.token()).status());
    }

    @Test
    void lostCommitAcknowledgementFailsClosedButDurableRevokeRemainsOnRestart() throws Exception {
        Paths paths = paths("lost-acknowledgement");
        var original = service(paths);
        UUID player = UUID.randomUUID();
        var issued = original.issue(player, "lost response");
        var observer = new SqliteCredentialDatabase.CommitObserver() {
            @Override public void afterCommit() throws IOException {
                throw new IOException("injected result unavailable after durable commit");
            }
        };
        var uncertain = new CredentialService(new SqliteCredentialStore(paths.snapshot()),
                new SqliteRevocationAuthority(paths.authority(), observer), 16);
        assertThrows(CredentialStoreUnavailableException.class,
                () -> uncertain.revoke(player, issued.credentialId()));
        assertEquals(CredentialService.Health.UNHEALTHY, uncertain.health());
        var restarted = service(paths);
        assertEquals(CredentialService.ResolveStatus.REVOKED,
                restarted.resolveAndTouch(issued.token()).status());
        assertTrue(restarted.revoke(player, issued.credentialId()).projectionUpdated());
    }

    @Test
    void contradictoryDomainAndUnknownSqliteSchemaFailClosed() throws Exception {
        Paths mismatch = paths("mismatch");
        service(mismatch);
        sql(mismatch.authority(), "UPDATE authority_state SET domain='" + UUID.randomUUID() + "'");
        assertEquals(CredentialService.Health.UNHEALTHY, service(mismatch).health());
        Paths schema = paths("schema");
        service(schema);
        sql(schema.snapshot(), "PRAGMA user_version=99");
        assertEquals(CredentialService.Health.UNHEALTHY, service(schema).health());
        assertEquals(99, scalar(schema.snapshot(), "PRAGMA user_version"));
    }

    @Test
    void invalidSnapshotPayloadDuplicateIdAndRoleMismatchFailClosed() throws Exception {
        for (String kind : List.of("invalid-json", "duplicate-id", "unknown-json-schema", "wrong-role")) {
            Paths paths = paths(kind);
            var service = service(paths);
            service.issue(UUID.randomUUID(), "record");
            try (var connection = connection(paths.snapshot()); var statement = connection.createStatement()) {
                if (kind.equals("wrong-role")) {
                    statement.executeUpdate("UPDATE backend_meta SET role='authority'");
                } else {
                    String payload;
                    try (var result = statement.executeQuery("SELECT payload FROM snapshot_state")) {
                        assertTrue(result.next());
                        payload = result.getString(1);
                    }
                    JsonObject snapshot = JsonParser.parseString(payload).getAsJsonObject();
                    switch (kind) {
                        case "invalid-json" -> payload = "broken json";
                        case "duplicate-id" -> {
                            snapshot.getAsJsonArray("records").add(
                                    snapshot.getAsJsonArray("records").get(0).deepCopy());
                            payload = snapshot.toString();
                        }
                        case "unknown-json-schema" -> {
                            snapshot.addProperty("schema_version", 99);
                            payload = snapshot.toString();
                        }
                    }
                    try (var update = connection.prepareStatement("UPDATE snapshot_state SET payload=?")) {
                        update.setString(1, payload);
                        update.executeUpdate();
                    }
                }
            }
            assertEquals(CredentialService.Health.UNHEALTHY, service(paths).health(), kind);
        }
    }

    @Test
    void corruptedExistingDatabaseAndNonRegularDatabaseFailClosedWithoutReplacement() throws Exception {
        Paths corrupt = paths("corrupt");
        service(corrupt);
        byte[] corruption = "existing broken database".getBytes(StandardCharsets.UTF_8);
        Files.write(corrupt.authority(), corruption);
        assertEquals(CredentialService.Health.UNHEALTHY, service(corrupt).health());
        assertArrayEquals(corruption, Files.readAllBytes(corrupt.authority()));
        Paths directory = paths("directory");
        service(directory);
        Files.delete(directory.snapshot());
        Files.createDirectory(directory.snapshot());
        assertEquals(CredentialService.Health.UNHEALTHY, service(directory).health());
        assertTrue(Files.isDirectory(directory.snapshot()));
    }

    @Test
    void pendingBootstrapRejectsAuthenticationAndExplicitContinuationCompletes() throws Exception {
        Paths paths = paths("pending");
        var authority = new SqliteRevocationAuthority(paths.authority());
        UUID domain = authority.beginBootstrap();
        new SqliteCredentialStore(paths.snapshot()).initialize(domain);
        CredentialService service = service(paths);
        assertEquals(CredentialService.Health.UNHEALTHY, service.health());
        assertThrows(CredentialStoreUnavailableException.class,
                () -> service.issue(UUID.randomUUID(), "blocked"));
        assertEquals(domain, service.bootstrap());
        assertEquals(CredentialService.Health.HEALTHY, service.health());
        assertFalse(authority.bootstrapPendingExists());
    }

    @Test
    void missingEitherBackendCreatesNewDomainAndResetRetainsOldState() throws Exception {
        for (boolean deleteSnapshot : List.of(true, false)) {
            Paths paths = paths("missing-" + deleteSnapshot);
            var original = service(paths);
            UUID domain = original.credentialDomainId();
            var issued = original.issueSession(UUID.randomUUID(), null, 7200);
            Files.delete(deleteSnapshot ? paths.snapshot() : paths.authority());
            var restarted = service(paths);
            assertEquals(CredentialService.Health.HEALTHY, restarted.health());
            assertNotEquals(domain, restarted.credentialDomainId());
            assertEquals(CredentialService.ResolveStatus.NOT_FOUND,
                    restarted.resolveAndTouch(issued.token()).status());
        }
        Paths reset = paths("reset");
        var service = service(reset);
        var issued = service.issue(UUID.randomUUID(), "old");
        var result = service.reset();
        assertNotNull(result.archivedSnapshot());
        assertNotNull(result.archivedAuthority());
        assertTrue(Files.exists(result.archivedSnapshot()));
        assertTrue(Files.exists(result.archivedAuthority()));
        assertEquals(CredentialService.ResolveStatus.NOT_FOUND,
                service.resolveAndTouch(issued.token()).status());
        assertTrue(scalar(reset.snapshot(), "SELECT count(*) FROM retired_snapshots") > 0);
        assertTrue(scalar(reset.authority(), "SELECT count(*) FROM retired_authority") > 0);
    }

    @Test
    void authorityBusyFailsClosedAndRestartKeepsCredentialUntilSuccessfulRetry() throws Exception {
        Paths paths = paths("busy");
        var service = service(paths);
        UUID player = UUID.randomUUID();
        var issued = service.issue(player, "busy");
        try (var connection = connection(paths.authority()); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            assertThrows(CredentialStoreUnavailableException.class,
                    () -> service.revoke(player, issued.credentialId()));
            assertEquals(CredentialService.Health.UNHEALTHY, service.health());
            statement.execute("ROLLBACK");
        }
        var restarted = service(paths);
        assertEquals(CredentialService.ResolveStatus.ACTIVE,
                restarted.resolveAndTouch(issued.token()).status());
        restarted.revoke(player, issued.credentialId());
        assertEquals(CredentialService.ResolveStatus.REVOKED,
                service(paths).resolveAndTouch(issued.token()).status());
    }

    @Test
    void diskIoFailureAfterStartupCannotRecreateAuthorityAndAuthenticatesNothing() throws Exception {
        Paths paths = paths("disk-io");
        var service = service(paths);
        UUID player = UUID.randomUUID();
        var issued = service.issue(player, "disk");
        Files.delete(paths.authority());
        Files.createDirectory(paths.authority());
        assertThrows(CredentialStoreUnavailableException.class,
                () -> service.revoke(player, issued.credentialId()));
        assertEquals(CredentialService.Health.UNHEALTHY, service.health());
        assertThrows(CredentialStoreUnavailableException.class,
                () -> service.resolveAndTouch(issued.token()));
    }

    @Test
    void sqliteFullBeforeAuthorityCommitRollsBackAndSuccessfulRetryRevokes() throws Exception {
        Paths paths = paths("sqlite-full");
        var original = service(paths);
        UUID player = UUID.randomUUID();
        var issued = original.issue(player, "capacity failure");
        var observer = new SqliteCredentialDatabase.CommitObserver() {
            @Override public void beforeCommit(Connection connection) throws IOException {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE pressure(payload BLOB)");
                    long currentPages;
                    try (var result = statement.executeQuery("PRAGMA page_count")) {
                        assertTrue(result.next());
                        currentPages = result.getLong(1);
                    }
                    statement.execute("PRAGMA max_page_count=" + currentPages);
                    SQLException full = assertThrows(SQLException.class,
                            () -> statement.executeUpdate("INSERT INTO pressure VALUES(zeroblob(1048576))"));
                    assertEquals(13, full.getErrorCode(), "must be real SQLite SQLITE_FULL");
                    throw new IOException("injected database capacity limit", full);
                } catch (SQLException e) {
                    throw new IOException("capacity test setup failed", e);
                }
            }
        };
        var failing = new CredentialService(new SqliteCredentialStore(paths.snapshot()),
                new SqliteRevocationAuthority(paths.authority(), observer), 16);
        assertThrows(CredentialStoreUnavailableException.class,
                () -> failing.revoke(player, issued.credentialId()));
        assertEquals(CredentialService.Health.UNHEALTHY, failing.health());
        assertEquals(0, scalar(paths.authority(), "SELECT count(*) FROM tombstones"));
        var restarted = service(paths);
        assertEquals(CredentialService.ResolveStatus.ACTIVE,
                restarted.resolveAndTouch(issued.token()).status());
        assertTrue(restarted.revoke(player, issued.credentialId()).projectionUpdated());
        assertEquals(CredentialService.ResolveStatus.REVOKED,
                service(paths).resolveAndTouch(issued.token()).status());
    }

    @Test
    void readOnlyAuthorityCannotReportSuccessfulRevoke() throws Exception {
        Paths paths = paths("read-only");
        var service = service(paths);
        UUID player = UUID.randomUUID();
        var issued = service.issue(player, "read-only");
        Set<PosixFilePermission> original = null;
        DosFileAttributeView dos = null;
        if (Files.getFileStore(paths.authority()).supportsFileAttributeView("posix")) {
            original = Files.getPosixFilePermissions(paths.authority());
            Files.setPosixFilePermissions(paths.authority(), Set.of(PosixFilePermission.OWNER_READ));
            if (Files.isWritable(paths.authority())) {
                Files.setPosixFilePermissions(paths.authority(), original);
                org.junit.jupiter.api.Assumptions.abort("privileged user can write read-only file");
            }
        } else {
            dos = Files.getFileAttributeView(paths.authority(), DosFileAttributeView.class);
            org.junit.jupiter.api.Assumptions.assumeTrue(dos != null, "no read-only file attribute support");
            dos.setReadOnly(true);
        }
        try {
            assertThrows(CredentialStoreUnavailableException.class,
                    () -> service.revoke(player, issued.credentialId()));
            assertEquals(CredentialService.Health.UNHEALTHY, service.health());
        } finally {
            if (original != null) Files.setPosixFilePermissions(paths.authority(), original);
            if (dos != null) dos.setReadOnly(false);
        }
        assertEquals(CredentialService.ResolveStatus.ACTIVE,
                service(paths).resolveAndTouch(issued.token()).status());
    }

    @Test
    void osSelectionUsesDistinctSqliteNamesAndNeverReadsOrModifiesLegacyWindowsFiles() throws Exception {
        Path root = temp.resolve("selection");
        Files.createDirectories(root);
        Path snapshot = root.resolve("snapshot.json");
        Path authority = root.resolve("authority");
        Files.writeString(snapshot, "broken legacy snapshot");
        Files.createDirectories(authority);
        Files.writeString(authority.resolve("manifest.json"), "broken legacy authority");
        var windows = CredentialService.forOperatingSystem(snapshot, authority, 16, "Windows 11");
        assertEquals(CredentialService.Health.HEALTHY, windows.health());
        assertEquals("broken legacy snapshot", Files.readString(snapshot));
        assertEquals("broken legacy authority", Files.readString(authority.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(root.resolve("snapshot.json.sqlite")));
        assertTrue(Files.isRegularFile(root.resolve("authority-sqlite/authority.sqlite")));
        for (String os : List.of("Linux", "Mac OS X")) {
            Path osRoot = temp.resolve(os);
            var file = CredentialService.forOperatingSystem(osRoot.resolve("snapshot.json"),
                    osRoot.resolve("authority"), 16, os);
            if (!System.getProperty("os.name").startsWith("Windows")) {
                assertEquals(CredentialService.Health.HEALTHY, file.health());
                assertEquals(1, JsonParser.parseString(Files.readString(osRoot.resolve("snapshot.json")))
                        .getAsJsonObject().get("schema_version").getAsInt());
                assertTrue(Files.exists(osRoot.resolve("authority/manifest.json")));
            } else {
                // Windowsにfile backendを強制すれば既知のdirectory-open問題になる。
                assertEquals(CredentialService.Health.UNHEALTHY, file.health());
                assertTrue(Files.isDirectory(osRoot.resolve("authority")));
            }
            assertFalse(Files.exists(osRoot.resolve("snapshot.json.sqlite")));
        }
    }

    @Test
    void crashImmediatelyBeforeAndAfterAuthorityCommitHasCorrectRestartOutcome() throws Exception {
        for (String stage : List.of("before", "after")) {
            Paths paths = paths("crash-" + stage);
            var original = service(paths);
            UUID player = UUID.randomUUID();
            var issued = original.issue(player, "crash");
            var record = new SqliteCredentialStore(paths.snapshot()).load().records().getFirst();
            Process child = new ProcessBuilder(javaExecutable(), "-cp", subprocessClasspath(),
                    SqliteCredentialCrashProbe.class.getName(), paths.authority().toString(), stage,
                    original.credentialDomainId().toString(), issued.credentialId().toString(),
                    record.tokenHash(), player.toString()).redirectErrorStream(true)
                    .redirectOutput(temp.resolve("crash-" + stage + ".log").toFile()).start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child JVM failed to stop");
                assertEquals(stage.equals("before") ? 71 : 72, child.exitValue(),
                        Files.readString(temp.resolve("crash-" + stage + ".log")));
            } finally { child.destroyForcibly(); }
            var restarted = service(paths);
            assertEquals(CredentialService.Health.HEALTHY, restarted.health());
            assertEquals(stage.equals("before") ? CredentialService.ResolveStatus.ACTIVE
                            : CredentialService.ResolveStatus.REVOKED,
                    restarted.resolveAndTouch(issued.token()).status());
            if (stage.equals("after")) {
                restarted.revoke(player, issued.credentialId());
                assertEquals(CredentialService.ResolveStatus.REVOKED,
                        service(paths).resolveAndTouch(issued.token()).status());
            }
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private static String subprocessClasspath() throws Exception {
        List<String> entries = new ArrayList<>();
        for (Class<?> type : List.of(SqliteCredentialCrashProbe.class, CredentialService.class,
                org.sqlite.JDBC.class, com.google.gson.Gson.class, org.slf4j.LoggerFactory.class)) {
            String path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            if (!entries.contains(path)) entries.add(path);
        }
        var resources = CredentialService.class.getClassLoader().getResource("plugin.yml");
        if (resources != null && resources.getProtocol().equals("file")) {
            entries.add(Path.of(resources.toURI()).getParent().toString());
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    private Paths paths(String name) {
        Path root = temp.resolve(name);
        return new Paths(root.resolve("store/snapshot.sqlite"), root.resolve("authority/authority.sqlite"));
    }

    private static CredentialService service(Paths paths) { return service(paths, 16); }
    private static CredentialService service(Paths paths, int activeLimit) {
        return new CredentialService(new SqliteCredentialStore(paths.snapshot()),
                new SqliteRevocationAuthority(paths.authority()), activeLimit);
    }

    private static RevocationAuthority.Tombstone tombstone(UUID domain) {
        return new RevocationAuthority.Tombstone(domain, UUID.randomUUID(), "test-hash",
                UUID.randomUUID(), Instant.parse("2026-10-09T00:00:00Z"));
    }

    private static Connection connection(Path path) throws Exception {
        return BundledSqliteDriver.connect("jdbc:sqlite:" + path.toAbsolutePath());
    }
    private static void sql(Path path, String sql) throws Exception {
        try (var connection = connection(path); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
    private static long scalar(Path path, String sql) throws Exception {
        try (var connection = connection(path); var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
    private record Paths(Path snapshot, Path authority) {}
}
