package club.code2create.mcremote;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PreAuthAdmissionTest {
    static PreAuthPolicy policy(int total, int pending, int rate) {
        return new PreAuthPolicy(total, pending, rate, 8, 1024, 1, 2, 2, 3, 2);
    }

    @Test void pendingAndTotalLimitsRemainIndependentAndCloseIsIdempotent() {
        PreAuthAdmission admission = new PreAuthAdmission(policy(2, 1, 20));
        var first = admission.acquire();
        assertNotNull(first);
        assertNull(admission.acquire());
        first.authenticated();
        first.authenticated();
        var second = admission.acquire();
        assertNotNull(second);
        second.authenticated();
        assertNull(admission.acquire());
        first.close(); first.close(); first.authenticated();
        var third = admission.acquire();
        assertNotNull(third);
        assertNull(admission.acquire());
        third.close(); second.close();
        assertNotNull(admission.acquire());
    }

    @Test void reconnectsCannotBypassRateAndPairBudgetsAreSeparate() {
        AtomicLong clock = new AtomicLong();
        PreAuthAdmission admission = new PreAuthAdmission(policy(4, 4, 2), clock::get);
        admission.acquire().close(); admission.acquire().close();
        assertNull(admission.acquire());
        clock.set(999_999_999L);
        assertNull(admission.acquire());
        clock.incrementAndGet();
        assertNotNull(admission.acquire());
        assertTrue(admission.allowPair("auth.pairBegin"));
        assertTrue(admission.allowPair("auth.pairBegin"));
        assertFalse(admission.allowPair("auth.pairBegin"));
        for (int i = 0; i < 3; i++) assertTrue(admission.allowPair("auth.pairPoll"));
        assertFalse(admission.allowPair("auth.pairPoll"));
        assertTrue(admission.allowPair("hello"));
        clock.addAndGet(1_000_000_000L);
        assertTrue(admission.allowPair("auth.pairBegin"));
        assertTrue(admission.allowPair("auth.pairPoll"));
    }

    @Test void configurationCannotDisableLimitsWithZeroOrNegativeValues() {
        MemoryConfiguration config = new MemoryConfiguration();
        assertEquals(32, PreAuthPolicy.from(config).maxPendingConnections());
        config.set("connection.max_connections", 0);
        assertThrows(IllegalArgumentException.class, () -> PreAuthPolicy.from(config));
        config.set("connection.max_connections", 3);
        config.set("auth.max_pending_pairs", -1);
        assertThrows(IllegalArgumentException.class, () -> PreAuthPolicy.from(config));
    }
}
