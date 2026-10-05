package club.code2create.mcremote;

import java.util.function.LongSupplier;

/** Global bounds also cover clients behind a shared Bridge address; no per-IP map grows here. */
final class PreAuthAdmission {
    private final PreAuthPolicy policy;
    private final LongSupplier clock;
    private final Rate accepts, begins, polls;
    private int connections, pending;

    PreAuthAdmission(PreAuthPolicy policy) { this(policy, System::nanoTime); }

    PreAuthAdmission(PreAuthPolicy policy, LongSupplier clock) {
        this.policy = policy;
        this.clock = clock;
        accepts = new Rate(policy.acceptsPerSecond());
        begins = new Rate(policy.pairBeginsPerSecond());
        polls = new Rate(policy.pairPollsPerSecond());
    }

    synchronized Lease acquire() {
        if (!accepts.allow(clock.getAsLong()) || connections >= policy.maxConnections()
                || pending >= policy.maxPendingConnections()) return null;
        connections++;
        pending++;
        return new Lease();
    }

    synchronized boolean allowPair(String method) {
        return switch (method) {
            case "auth.pairBegin" -> begins.allow(clock.getAsLong());
            case "auth.pairPoll" -> polls.allow(clock.getAsLong());
            default -> true;
        };
    }

    final class Lease implements AutoCloseable {
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
