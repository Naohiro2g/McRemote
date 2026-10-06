package club.code2create.mcremote;

import java.util.function.LongSupplier;

/** Global bounds also cover clients behind a shared Bridge address; no per-IP map grows here. */
final class PreAuthAdmission {
    private final PreAuthPolicy policy;
    private final LongSupplier clock;
    private final ConnectionLimitStats stats;
    private final Rate accepts, begins, polls;
    private int connections, pending;

    PreAuthAdmission(PreAuthPolicy policy) { this(policy, System::nanoTime); }

    PreAuthAdmission(PreAuthPolicy policy, LongSupplier clock) {
        this(policy, clock, new ConnectionLimitStats());
    }

    PreAuthAdmission(PreAuthPolicy policy, ConnectionLimitStats stats) {
        this(policy, System::nanoTime, stats);
    }

    PreAuthAdmission(PreAuthPolicy policy, LongSupplier clock, ConnectionLimitStats stats) {
        this.policy = policy;
        this.clock = clock;
        this.stats = stats;
        accepts = new Rate(policy.acceptsPerSecond());
        begins = new Rate(policy.pairBeginsPerSecond());
        polls = new Rate(policy.pairPollsPerSecond());
    }

    synchronized Lease acquire() {
        ConnectionLimitStats.Reason reason = !accepts.allow(clock.getAsLong())
                ? ConnectionLimitStats.Reason.ACCEPT_RATE
                : connections >= policy.maxConnections() ? ConnectionLimitStats.Reason.CONNECTIONS
                : pending >= policy.maxPendingConnections() ? ConnectionLimitStats.Reason.PRE_HELLO_CONNECTIONS
                : null;
        if (reason != null) { stats.rejected(reason); return null; }
        connections++;
        pending++;
        stats.connections(connections, pending);
        return new Lease();
    }

    synchronized boolean allowPair(String method) {
        boolean allowed = switch (method) {
            case "auth.pairBegin" -> begins.allow(clock.getAsLong());
            case "auth.pairPoll" -> polls.allow(clock.getAsLong());
            default -> true;
        };
        if (!allowed) stats.rejected("auth.pairBegin".equals(method)
                ? ConnectionLimitStats.Reason.PAIR_BEGIN_RATE : ConnectionLimitStats.Reason.PAIR_POLL_RATE);
        return allowed;
    }

    final class Lease implements AutoCloseable {
        ConnectionLimitStats stats() { return stats; }
        private boolean authenticated, closed;
        void authenticated() {
            synchronized (PreAuthAdmission.this) {
                if (!closed && !authenticated) { authenticated = true; pending--; }
            }
        }
        @Override public void close() {
            synchronized (PreAuthAdmission.this) {
                if (closed) return;
                closed = true;
                connections--;
                if (!authenticated) pending--;
            }
        }
    }

    private static final class Rate {
        private final int limit;
        private long start;
        private int count;
        Rate(int limit) { this.limit = limit; }
        boolean allow(long now) {
            if (count == 0 || now - start >= 1_000_000_000L) { start = now; count = 0; }
            if (count >= limit) return false;
            count++;
            return true;
        }
    }
}
