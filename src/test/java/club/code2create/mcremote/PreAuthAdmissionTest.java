package club.code2create.mcremote;

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

    @Test void sixteenPlayersAndFourHelpersCanCompleteFourConnectionSetupPhasesInOneWindow() {
        ConnectionLimitStats stats = new ConnectionLimitStats();
        PreAuthAdmission admission = new PreAuthAdmission(PreAuthPolicy.forMaxPlayers(16, 120), () -> 0, stats);
        for (int phase = 0; phase < 4; phase++) {
            for (int person = 0; person < 20; person++) {
                var lease = admission.acquire();
                assertNotNull(lease, "phase=" + phase + " person=" + person);
                if (phase == 1) assertTrue(admission.allowPair("auth.pairBegin"));
                if (phase == 2) assertTrue(admission.allowPair("auth.pairPoll"));
                if (phase == 3) lease.authenticated();
                else lease.close();
            }
        }
        assertFalse(admission.allowPair("auth.pairBegin"));
        assertNull(admission.acquire());
        String summary = stats.drainSummary();
        assertTrue(summary.contains("ACCEPT_RATE=1"));
        assertTrue(summary.contains("PAIR_BEGIN_RATE=1"));
        assertTrue(summary.contains("connection_peak=20"));
    }
}
