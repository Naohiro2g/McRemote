package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingManagerTest {
    @Test
    void sessionPairingAndTokenResolutionRemainUnchanged() {
        TokenStore tokenStore = new TokenStore(null);
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
