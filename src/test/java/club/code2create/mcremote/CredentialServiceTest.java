package club.code2create.mcremote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialServiceTest {
    @TempDir
    Path temp;

    @Test
    void longLivedCredentialSurvivesRestartAndNeverStoresRawToken() throws Exception {
        Paths paths = paths();
        CredentialService service = new CredentialService(paths.snapshot(), paths.authority(), 16);
        assertEquals(CredentialService.Health.UNINITIALIZED, service.health());
        service.bootstrap();

        UUID player = UUID.randomUUID();
        CredentialService.IssueResult issued = service.issue(player, "教室PC-3");
        assertTrue(issued.token().startsWith("mcrl_"));
        assertFalse(Files.readString(paths.snapshot()).contains(issued.token()));

        CredentialService restarted = new CredentialService(paths.snapshot(), paths.authority(), 16);
        CredentialService.ResolveResult resolved = restarted.resolveAndTouch(issued.token());
        assertEquals(CredentialService.ResolveStatus.ACTIVE, resolved.status());
        assertEquals(player, resolved.record().playerUuid());
        assertEquals(issued.credentialId(), resolved.record().credentialId());
    }

    @Test
    void authorityOverlayKeepsRevokeAfterSnapshotRollback() throws Exception {
        Paths paths = paths();
        CredentialService service = initialized(paths);
        UUID player = UUID.randomUUID();
        CredentialService.IssueResult issued = service.issue(player, "laptop");
        byte[] beforeRevoke = Files.readAllBytes(paths.snapshot());

        CredentialService.RevokeResult revoked = service.revoke(player, issued.credentialId());
        assertTrue(revoked.projectionUpdated());
        Files.write(paths.snapshot(), beforeRevoke);

        CredentialService restarted = new CredentialService(paths.snapshot(), paths.authority(), 16);
        assertEquals(CredentialService.Health.HEALTHY, restarted.health());
        assertEquals(CredentialService.ResolveStatus.REVOKED,
                restarted.resolveAndTouch(issued.token()).status());
        assertTrue(restarted.list(player).isEmpty());
    }

    @Test
    void snapshotFailureAfterAuthorityCommitStaysSuccessfulAndReconciles() throws Exception {
        Paths paths = paths();
        CredentialService original = initialized(paths);
        UUID player = UUID.randomUUID();
        CredentialService.IssueResult issued = original.issue(player, "desktop");

        FailingCredentialStore failingStore = new FailingCredentialStore(paths.snapshot());
        CredentialService service = new CredentialService(
                failingStore, new RevocationAuthority(paths.authority()), 16);
        failingStore.failPersist = true;
        CredentialService.RevokeResult result = service.revoke(player, issued.credentialId());

        assertFalse(result.projectionUpdated());
        assertEquals(CredentialService.Health.DEGRADED, service.health());
        assertEquals(CredentialService.ResolveStatus.REVOKED,
                service.resolveAndTouch(issued.token()).status());
        assertTrue(service.list(player).isEmpty());

        failingStore.failPersist = false;
        assertTrue(service.reconcileIfNeeded());
        assertEquals(CredentialService.Health.HEALTHY, service.health());
    }

    @Test
    void authorityFailureBeforeLinearizationReturnsUnavailableAndFailsClosed() throws Exception {
        Paths paths = paths();
        CredentialService original = initialized(paths);
        UUID player = UUID.randomUUID();
        CredentialService.IssueResult issued = original.issue(player, "desktop");

        RevocationAuthority failingAuthority = new RevocationAuthority(paths.authority()) {
            @Override
            Tombstone commit(Tombstone tombstone) throws IOException {
                throw new IOException("injected authority failure");
            }
        };
        CredentialService service = new CredentialService(
                new CredentialStore(paths.snapshot()), failingAuthority, 16);

        CredentialStoreUnavailableException error = assertThrows(
                CredentialStoreUnavailableException.class,
                () -> service.revoke(player, issued.credentialId()));
        assertEquals(CredentialStoreUnavailableException.Operation.REVOKE, error.operation());
        assertEquals(CredentialService.Health.UNHEALTHY, service.health());
        assertThrows(CredentialStoreUnavailableException.class,
                () -> service.resolveAndTouch(issued.token()));
    }

    @Test
    void missingAuthorityAndDomainMismatchNeverBecomeEmptyStore() throws Exception {
        Paths missing = paths("missing");
        CredentialService service = initialized(missing);
        Files.delete(missing.authority().resolve("manifest.json"));
        CredentialService withoutAuthority = new CredentialService(
                missing.snapshot(), missing.authority(), 16);
        assertEquals(CredentialService.Health.UNHEALTHY, withoutAuthority.health());

        Paths mismatch = paths("mismatch");
        initialized(mismatch);
        String manifest = Files.readString(mismatch.authority().resolve("manifest.json"));
        String currentDomain = extractDomain(manifest);
        Files.writeString(mismatch.authority().resolve("manifest.json"),
                manifest.replace(currentDomain, UUID.randomUUID().toString()), StandardCharsets.UTF_8);
        CredentialService mismatched = new CredentialService(
                mismatch.snapshot(), mismatch.authority(), 16);
        assertEquals(CredentialService.Health.UNHEALTHY, mismatched.health());
    }

    @Test
    void createOnlyAuthorityIsIdempotentButRejectsContradictionAndCorruption() throws Exception {
        Path authorityPath = temp.resolve("authority-direct");
        RevocationAuthority authority = new RevocationAuthority(authorityPath);
        UUID domain = authority.beginBootstrap();
        authority.completeBootstrap(domain);
        UUID credentialId = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        RevocationAuthority.Tombstone first = new RevocationAuthority.Tombstone(
                domain, credentialId, "hash-a", player, Instant.parse("2026-08-02T00:00:00Z"));
        assertEquals(first, authority.commit(first));
        assertEquals(first, authority.commit(first));

        RevocationAuthority.Tombstone contradiction = new RevocationAuthority.Tombstone(
                domain, credentialId, "hash-b", player, first.revokedAt());
        assertThrows(IOException.class, () -> authority.commit(contradiction));

        Path finalPath = authorityPath.resolve("revoked-" + UUID.randomUUID() + ".json");
        Files.writeString(finalPath, "not-json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> authority.loadTombstones(domain));
    }

    @Test
    void concurrentCreateOnlyPublishAllowsOnlyOneContradictoryWinner() throws Exception {
        Path authorityPath = temp.resolve("authority-race");
        RevocationAuthority authority = new RevocationAuthority(authorityPath);
        UUID domain = authority.beginBootstrap();
        authority.completeBootstrap(domain);
        UUID credentialId = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        RevocationAuthority.Tombstone a = new RevocationAuthority.Tombstone(
                domain, credentialId, "hash-a", player, now);
        RevocationAuthority.Tombstone b = new RevocationAuthority.Tombstone(
                domain, credentialId, "hash-b", player, now);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RevocationAuthority.Tombstone> first = executor.submit(() -> {
                start.await();
                return authority.commit(a);
            });
            Future<RevocationAuthority.Tombstone> second = executor.submit(() -> {
                start.await();
                return authority.commit(b);
            });
            start.countDown();
            int successes = 0;
            int failures = 0;
            for (Future<RevocationAuthority.Tombstone> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof IOException);
                    failures++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, failures);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void authorityRejectsSymlinkFinal() throws Exception {
        Path authorityPath = temp.resolve("authority-symlink");
        RevocationAuthority authority = new RevocationAuthority(authorityPath);
        UUID domain = authority.beginBootstrap();
        authority.completeBootstrap(domain);
        UUID credentialId = UUID.randomUUID();
        Path outside = temp.resolve("outside.json");
        Files.writeString(outside, "{}", StandardCharsets.UTF_8);
        Path link = authorityPath.resolve("revoked-" + credentialId + ".json");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlink unavailable: " + e.getMessage());
        }
        RevocationAuthority.Tombstone tombstone = new RevocationAuthority.Tombstone(
                domain, credentialId, "hash", UUID.randomUUID(), Instant.now());
        assertThrows(IOException.class, () -> authority.commit(tombstone));
    }

    @Test
    void activeCredentialLimitDoesNotAutoRevokeOldCredential() throws Exception {
        Paths paths = paths("limit");
        CredentialService service = new CredentialService(paths.snapshot(), paths.authority(), 1);
        service.bootstrap();
        UUID player = UUID.randomUUID();
        CredentialService.IssueResult first = service.issue(player, "first");
        CredentialLimitReachedException error = assertThrows(
                CredentialLimitReachedException.class,
                () -> service.issue(player, "second"));
        assertEquals(1, error.limit());
        assertEquals(1, error.active());
        assertEquals(CredentialService.ResolveStatus.ACTIVE,
                service.resolveAndTouch(first.token()).status());
    }

    @Test
    void duplicateSnapshotIdAndIncompleteBootstrapFailClosed() throws Exception {
        Paths duplicate = paths("duplicate");
        CredentialService service = initialized(duplicate);
        service.issue(UUID.randomUUID(), "one");
        JsonObject snapshot = JsonParser.parseString(Files.readString(duplicate.snapshot()))
                .getAsJsonObject();
        snapshot.getAsJsonArray("records").add(
                snapshot.getAsJsonArray("records").get(0).deepCopy());
        Files.writeString(duplicate.snapshot(), snapshot.toString(), StandardCharsets.UTF_8);
        assertEquals(CredentialService.Health.UNHEALTHY,
                new CredentialService(duplicate.snapshot(), duplicate.authority(), 16).health());

        Paths pending = paths("pending");
        CredentialStore store = new CredentialStore(pending.snapshot());
        RevocationAuthority authority = new RevocationAuthority(pending.authority());
        UUID domain = authority.beginBootstrap();
        store.initialize(domain);
        assertEquals(CredentialService.Health.UNHEALTHY,
                new CredentialService(pending.snapshot(), pending.authority(), 16).health());
    }

    @Test
    void backendDisappearanceWhileRunningFailsClosed() throws Exception {
        Paths paths = paths("disappeared");
        CredentialService service = initialized(paths);
        CredentialService.IssueResult issued = service.issue(UUID.randomUUID(), "one");
        Files.delete(paths.snapshot());
        CredentialStoreUnavailableException error = assertThrows(
                CredentialStoreUnavailableException.class,
                () -> service.resolveAndTouch(issued.token()));
        assertEquals(CredentialStoreUnavailableException.Operation.RESOLVE, error.operation());
        assertEquals(CredentialService.Health.UNHEALTHY, service.health());
    }

    @Test
    void oldPrefixIsNotMigratedAndUnknownTokenTypeIsRejected() throws Exception {
        Paths paths = paths("prefix");
        CredentialService service = new CredentialService(paths.snapshot(), paths.authority(), 16);
        TokenStore tokens = new TokenStore(service);
        assertEquals(TokenStore.ResolveStatus.NOT_FOUND,
                tokens.resolve("mcrp_legacy-client-value").status());
        assertEquals(TokenStore.ResolveStatus.INVALID,
                tokens.resolve("unknown_value").status());
        assertEquals(TokenStore.ResolveStatus.STORE_UNAVAILABLE,
                tokens.resolve("mcrl_unbootstrapped").status());
        assertEquals(CredentialStoreUnavailableException.Operation.RESOLVE,
                tokens.resolve("mcrl_unbootstrapped").operation());
        assertThrows(IllegalArgumentException.class,
                () -> TokenStore.TokenType.fromWire("player"));
        assertEquals(TokenStore.TokenType.SESSION, TokenStore.TokenType.fromWire(null));
        assertEquals(TokenStore.TokenType.LONG_LIVED,
                TokenStore.TokenType.fromWire("long_lived"));
    }

    @Test
    void resetCreatesNewDomainAndArchivesOldState() throws Exception {
        Paths paths = paths("reset");
        CredentialService service = initialized(paths);
        UUID oldDomain = service.credentialDomainId();
        CredentialService.IssueResult issued = service.issue(UUID.randomUUID(), "old");

        CredentialService.ResetResult reset = service.reset();
        assertNotNull(reset.archivedSnapshot());
        assertNotNull(reset.archivedAuthority());
        assertTrue(Files.exists(reset.archivedSnapshot()));
        assertTrue(Files.exists(reset.archivedAuthority()));
        assertFalse(oldDomain.equals(reset.credentialDomainId()));
        assertEquals(CredentialService.ResolveStatus.NOT_FOUND,
                service.resolveAndTouch(issued.token()).status());
    }

    private CredentialService initialized(Paths paths) throws Exception {
        CredentialService service = new CredentialService(paths.snapshot(), paths.authority(), 16);
        service.bootstrap();
        return service;
    }

    private Paths paths() {
        return paths("default");
    }

    private Paths paths(String name) {
        Path root = temp.resolve(name);
        return new Paths(root.resolve("store/snapshot.json"), root.resolve("authority"));
    }

    private static String extractDomain(String json) {
        String marker = "\"credential_domain_id\": \"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private record Paths(Path snapshot, Path authority) {}

    private static final class FailingCredentialStore extends CredentialStore {
        boolean failPersist;

        FailingCredentialStore(Path path) {
            super(path);
        }

        @Override
        void persist(UUID domainId, List<CredentialRecord> records) throws IOException {
            if (failPersist) {
                throw new IOException("injected snapshot failure");
            }
            super.persist(domainId, records);
        }
    }
}
