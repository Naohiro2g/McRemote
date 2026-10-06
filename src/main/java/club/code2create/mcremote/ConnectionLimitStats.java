package club.code2create.mcremote;

import java.util.EnumMap;

/** Fixed-size, server-local diagnostics. Never retains peer identity or request contents. */
final class ConnectionLimitStats {
    enum Reason {
        ACCEPT_RATE, CONNECTIONS, PRE_HELLO_CONNECTIONS, PAIR_BEGIN_RATE, PAIR_POLL_RATE,
        PENDING_PAIRS, FRAME_BYTES, IDLE_TIMEOUT, HELLO_DEADLINE, COMMAND_QUEUE_BYTES
    }

    private final EnumMap<Reason, Long> rejected = new EnumMap<>(Reason.class);
    private int connectionPeak, preHelloPeak, pendingPairPeak, frameBytePeak;
    private int queueBytePeak, queueDepthPeak;
    private long queueWaits;
    private boolean changed;

    synchronized void rejected(Reason reason) {
        rejected.merge(reason, 1L, Long::sum);
        changed = true;
    }

    synchronized void connections(int total, int preHello) {
        changed |= total > connectionPeak || preHello > preHelloPeak;
        connectionPeak = Math.max(connectionPeak, total);
        preHelloPeak = Math.max(preHelloPeak, preHello);
    }

    synchronized void pendingPairs(int count) {
        changed |= count > pendingPairPeak;
        pendingPairPeak = Math.max(pendingPairPeak, count);
    }

    synchronized void frameBytes(int bytes) {
        changed |= bytes > frameBytePeak;
        frameBytePeak = Math.max(frameBytePeak, bytes);
    }

    synchronized void queue(int bytes, int depth) {
        changed |= bytes > queueBytePeak || depth > queueDepthPeak;
        queueBytePeak = Math.max(queueBytePeak, bytes);
        queueDepthPeak = Math.max(queueDepthPeak, depth);
    }

    synchronized void queueWait() {
        queueWaits++;
        changed = true;
    }

    /** Interval counts and lifetime high-water marks; null avoids an idle log heartbeat. */
    synchronized String drainSummary() {
        if (!changed) return null;
        String summary = "Connection limits: rejected=" + rejected
                + " connection_peak=" + connectionPeak + " pre_hello_peak=" + preHelloPeak
                + " pending_pair_peak=" + pendingPairPeak + " frame_bytes_peak=" + frameBytePeak
                + " queue_bytes_peak=" + queueBytePeak + " queue_depth_peak=" + queueDepthPeak
                + " queue_waits=" + queueWaits;
        rejected.clear();
        queueWaits = 0;
        changed = false;
        return summary;
    }
}
