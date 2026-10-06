package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionLimitStatsTest {
    @Test void totalAndPreHelloRefusalsHaveDifferentReasons() {
        ConnectionLimitStats stats = new ConnectionLimitStats();
        PreAuthAdmission admission = new PreAuthAdmission(PreAuthAdmissionTest.policy(2, 1, 20), () -> 0, stats);
        var first = admission.acquire();
        assertNull(admission.acquire());
        first.authenticated();
        var second = admission.acquire();
        second.authenticated();
        assertNull(admission.acquire());
        String summary = stats.drainSummary();
        assertTrue(summary.contains("CONNECTIONS=1"));
        assertTrue(summary.contains("PRE_HELLO_CONNECTIONS=1"));
        assertTrue(summary.contains("connection_peak=2"));
        assertTrue(summary.contains("pre_hello_peak=1"));
        assertNull(stats.drainSummary());
        first.close(); second.close();
        assertNull(stats.drainSummary(), "normal closes do not create rejection diagnostics");
    }

    @Test void countsDrainWhilePeaksSurviveAndConcurrentRejectionsAreNotLost() throws Exception {
        ConnectionLimitStats stats = new ConnectionLimitStats();
        stats.queue(100, 2);
        stats.queueWait();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) jobs.add(executor.submit(() -> {
                for (int j = 0; j < 100; j++) stats.rejected(ConnectionLimitStats.Reason.PAIR_POLL_RATE);
            }));
            for (var job : jobs) job.get(2, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        String summary = stats.drainSummary();
        assertTrue(summary.contains("PAIR_POLL_RATE=400"));
        assertTrue(summary.contains("queue_waits=1"));
        assertNull(stats.drainSummary());
        stats.queue(90, 1);
        assertNull(stats.drainSummary(), "lower usage does not produce a log heartbeat");
        stats.rejected(ConnectionLimitStats.Reason.IDLE_TIMEOUT);
        summary = stats.drainSummary();
        assertTrue(summary.contains("rejected={IDLE_TIMEOUT=1}"));
        assertTrue(summary.contains("queue_bytes_peak=100"));
        assertTrue(summary.contains("queue_waits=0"));
    }
}
