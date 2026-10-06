package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreAuthPolicyTest {
    @Test void thresholdsAreInclusiveAndOversizedServersKeepFiniteLargestProfile() {
        assertEquals(16, PreAuthPolicy.profilePlayers(0));
        assertEquals(16, PreAuthPolicy.profilePlayers(16));
        assertEquals(24, PreAuthPolicy.profilePlayers(17));
        assertEquals(24, PreAuthPolicy.profilePlayers(24));
        assertEquals(32, PreAuthPolicy.profilePlayers(25));
        assertEquals(32, PreAuthPolicy.profilePlayers(32));
        assertEquals(PreAuthPolicy.forMaxPlayers(32, 120), PreAuthPolicy.forMaxPlayers(Integer.MAX_VALUE, 120));
    }

    @Test void everyProfileHasRoomForNormalPollingAndOnePendingPairPerPlayer() {
        for (int players : new int[]{16, 24, 32}) {
            PreAuthPolicy policy = PreAuthPolicy.forMaxPlayers(players, 120);
            assertTrue(policy.maxConnections() >= 2 * (players + 4));
            assertTrue(policy.maxPendingConnections() >= players + 4);
            assertTrue(policy.maxPendingPairs() >= players + 4);
            assertTrue(policy.pairBeginsPerSecond() >= players + 4);
            assertTrue(policy.pairPollsPerSecond() >= 2 * (players + 4));
            assertTrue(policy.acceptsPerSecond() >= 4 * (players + 4));
            assertEquals(65_536, policy.maxFrameBytes());
            assertEquals(1_048_576, policy.commandQueueBytes());
            assertEquals(30, policy.idleSeconds());
        }
    }

    @Test void longerPairingWindowKeepsHelloMarginWithoutOverflow() {
        assertEquals(180, PreAuthPolicy.forMaxPlayers(16, 120).helloSeconds());
        assertEquals(360, PreAuthPolicy.forMaxPlayers(16, 300).helloSeconds());
        assertThrows(IllegalArgumentException.class, () -> PreAuthPolicy.forMaxPlayers(16, 0));
        assertThrows(IllegalArgumentException.class, () -> PreAuthPolicy.forMaxPlayers(16, Long.MAX_VALUE));
    }
}
