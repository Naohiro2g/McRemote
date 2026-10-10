package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Production startup, stop and reinitialization, on every OS/runtime in CI. */
class SqliteCredentialLifecycleTest {
    @TempDir Path temp;

    @Test
    void validLegacySessionIsNotMigratedAndExistingSqliteSessionContinues() throws Exception {
        Path snapshot = temp.resolve("snapshot.json"), authority = temp.resolve("authority");
        UUID oldDomain = UUID.randomUUID();
        String oldToken = "mcrs_legacy_session_for_test";
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(oldToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var record = new CredentialStore.CredentialRecord(UUID.randomUUID(), hash, UUID.randomUUID(),
                "session", null, Instant.now(), Instant.now(), Instant.now().plusSeconds(7200), null);
        String json = CredentialStore.encode(oldDomain, List.of(record));
        CredentialStore.decode(json);
        Files.writeString(snapshot, json); Files.createDirectories(authority);
        String manifest = "{\"schema_version\":1,\"credential_domain_id\":\"" + oldDomain + "\"}";
        Files.writeString(authority.resolve("manifest.json"), manifest);
        String token; UUID newDomain;
        try (var service = new CredentialService(snapshot, authority, 16)) {
            assertEquals(CredentialService.Health.HEALTHY, service.health());
            newDomain = service.credentialDomainId(); assertNotEquals(oldDomain, newDomain);
            assertEquals(CredentialService.ResolveStatus.NOT_FOUND, service.resolveAndTouch(oldToken).status());
            token = service.issueSession(UUID.randomUUID(), null, 7200).token();
        }
        try (var restarted = new CredentialService(snapshot, authority, 16)) {
            assertEquals(newDomain, restarted.credentialDomainId());
            assertEquals(CredentialService.ResolveStatus.ACTIVE, restarted.resolveAndTouch(token).status());
        }
        assertEquals(json, Files.readString(snapshot));
        assertEquals(manifest, Files.readString(authority.resolve("manifest.json")));
        try (var files = Files.list(temp)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains("retired")));
        }
    }

    @Test
    void connectionsFromDifferentBackendObjectsCloseBeforeNextConnectionStarts() throws Exception {
        Path snapshot = temp.resolve("snapshot.json"), authority = temp.resolve("authority");
        try (var initialized = new CredentialService(snapshot, authority, 16)) {
            assertEquals(CredentialService.Health.HEALTHY, initialized.health());
        }
        Path db = temp.resolve("snapshot.json.sqlite");
        var first = new SqliteCredentialDatabase(db, "snapshot");
        var second = new SqliteCredentialDatabase(db, "snapshot");
        var held = new AtomicReference<Connection>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), started = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var read = threads.submit(() -> first.read(connection -> {
                held.set(connection); entered.countDown(); await(release); return null;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var write = threads.submit(() -> {
                started.countDown();
                return second.write(connection -> {
                    assertTrue(held.get().isClosed(), "previous connection must close before the next opens");
                    return null;
                });
            });
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> write.get(150, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            read.get(10, TimeUnit.SECONDS); write.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test
    void startupWaitsForPriorConnectionAndContinuesCommittedDomain() throws Exception {
        Path snapshot = temp.resolve("snapshot.json"), authority = temp.resolve("authority");
        UUID domain;
        try (var original = new CredentialService(snapshot, authority, 16)) {
            domain = original.credentialDomainId();
        }
        var heldConnection = new AtomicReference<Connection>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), started = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var read = threads.submit(() -> new SqliteCredentialDatabase(temp.resolve("snapshot.json.sqlite"), "snapshot")
                    .read(connection -> {
                        heldConnection.set(connection); entered.countDown(); await(release); return null;
                    }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var start = threads.submit(() -> {
                started.countDown(); return new CredentialService(snapshot, authority, 16);
            });
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> start.get(150, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            read.get(10, TimeUnit.SECONDS);
            try (var restarted = start.get(10, TimeUnit.SECONDS)) {
                assertTrue(heldConnection.get().isClosed());
                assertEquals(CredentialService.Health.HEALTHY, restarted.health());
                assertEquals(domain, restarted.credentialDomainId());
            }
        } finally { release.countDown(); }
    }

    @Test
    void stopWaitsForInFlightOperationAndStaleServiceCannotReinitialize() throws Exception {
        Path snapshot = temp.resolve("snapshot.json"), authority = temp.resolve("authority");
        var service = new CredentialService(snapshot, authority, 16);
        String token = service.issueSession(UUID.randomUUID(), null, 7200).token();
        UUID domain = service.credentialDomainId();
        Path db = temp.resolve("snapshot.json.sqlite");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), operationEntered = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(3)) {
            var held = threads.submit(() -> new SqliteCredentialDatabase(db, "snapshot").read(connection -> {
                entered.countDown(); await(release); return null;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var resolve = threads.submit(() -> {
                synchronized (service) { operationEntered.countDown(); return service.resolveAndTouch(token); }
            });
            assertTrue(operationEntered.await(10, TimeUnit.SECONDS));
            var stop = threads.submit(service::close);
            try { assertThrows(TimeoutException.class, () -> stop.get(150, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            held.get(10, TimeUnit.SECONDS);
            assertEquals(CredentialService.ResolveStatus.ACTIVE, resolve.get(10, TimeUnit.SECONDS).status());
            stop.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); service.close(); }
        assertThrows(CredentialStoreUnavailableException.class, () -> service.resolveAndTouch(token));
        assertThrows(IOException.class, service::reset); assertThrows(IOException.class, service::bootstrap);
        try (var restarted = new CredentialService(snapshot, authority, 16)) {
            assertEquals(domain, restarted.credentialDomainId());
            assertEquals(CredentialService.ResolveStatus.ACTIVE, restarted.resolveAndTouch(token).status());
            restarted.reset();
            assertEquals(CredentialService.ResolveStatus.NOT_FOUND, restarted.resolveAndTouch(token).status());
        }
    }

    @Test
    void missingDriverReportsProviderCauseWithoutFallback() {
        IOException failure = assertThrows(IOException.class,
                () -> PaperSqliteDriver.loadDriver(new ClassLoader(null) {}));
        assertInstanceOf(ClassNotFoundException.class, failure.getCause());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("test coordination timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
}
