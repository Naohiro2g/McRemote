package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class PairingManagerTest {
    @TempDir
    Path temp;

    @Test
    void pendingCapCannotBeExceededByParallelBegins() throws Exception {
        ConnectionLimitStats stats = new ConnectionLimitStats();
        PairingManager pairing = new PairingManager(mock(TokenStore.class), 120, 7200, 2, stats);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 16; i++) jobs.add(executor.submit(() -> {
                try { pairing.begin(TokenStore.TokenType.SESSION, null); return true; }
                catch (PairingManager.CapacityExceeded expected) { return false; }
            }));
            int accepted = 0;
            for (var job : jobs) if (job.get(2, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertEquals(2, accepted);
            String summary = stats.drainSummary();
            assertTrue(summary.contains("PENDING_PAIRS=14"));
            assertTrue(summary.contains("pending_pair_peak=2"));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void consumingPairFreesPendingCapacityAndExpiryCanBeSwept() throws Exception {
        CredentialService credentials = new CredentialService(temp.resolve("snapshot.json"), temp.resolve("authority"), 16);
        PairingManager pairing = new PairingManager(new TokenStore(credentials), 120, 7200, 1);
        var first = pairing.begin(TokenStore.TokenType.SESSION, null);
        assertThrows(PairingManager.CapacityExceeded.class,
                () -> pairing.begin(TokenStore.TokenType.SESSION, null));
        pairing.bind(first.pairCode(), UUID.randomUUID());
        assertInstanceOf(PairingManager.Ok.class, pairing.poll(first.pairingId()));
        assertTrue(pairing.begin(TokenStore.TokenType.SESSION, null).pairCode().matches("[0-9]{6}"));
        PairingManager expired = new PairingManager(mock(TokenStore.class), -1, 7200, 1);
        expired.begin(TokenStore.TokenType.SESSION, null);
        expired.begin(TokenStore.TokenType.SESSION, null); // Expired entries are swept before capacity admission.
    }

    @Test
    void sessionPairingAndTokenResolutionRemainUnchanged() throws Exception {
        CredentialService credentials = new CredentialService(
                temp.resolve("snapshot.json"), temp.resolve("authority"), 16);
        TokenStore tokenStore = new TokenStore(credentials);
        PairingManager pairing = new PairingManager(tokenStore, 120, 7200);
        UUID player = UUID.randomUUID();

        PairingManager.BeginResult begin = pairing.begin(TokenStore.TokenType.SESSION, null);
        assertEquals(120, begin.expiresIn());
        assertTrue(begin.pairCode().matches("[0-9]{6}"));
        assertInstanceOf(PairingManager.Pending.class, pairing.poll(begin.pairingId()));
        assertEquals(PairingManager.BindStatus.OK, pairing.bind(begin.pairCode(), player));

        PairingManager.Ok paired = assertInstanceOf(
                PairingManager.Ok.class, pairing.poll(begin.pairingId()));
        assertTrue(paired.token().startsWith("mcrs_"));
        TokenStore.ResolveResult resolved = tokenStore.resolve(paired.token());
        assertEquals(TokenStore.ResolveStatus.ACTIVE, resolved.status());
        assertEquals(player, resolved.record().uuid());
        assertInstanceOf(PairingManager.PairNotFound.class, pairing.poll(begin.pairingId()));
    }
}
