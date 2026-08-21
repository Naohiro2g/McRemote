package club.code2create.mcremote;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Bounded response-frame FIFO for one connection.
 *
 * <p>Saturation is terminal: the queue reports failure, clears retained frames, and rejects every
 * later frame. The owning {@link RemoteSession} then closes the transport, so a dropped frame can
 * never be mistaken for a successful {@code connection.flush} response.</p>
 */
final class ConnectionFrameQueue {
    private final int capacity;
    private final ArrayDeque<String> frames = new ArrayDeque<>();
    private boolean failed;

    ConnectionFrameQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    synchronized boolean offer(String frame) {
        Objects.requireNonNull(frame, "frame");
        if (failed) {
            return false;
        }
        if (frames.size() >= capacity) {
            failed = true;
            frames.clear();
            return false;
        }
        frames.addLast(frame);
        return true;
    }

    synchronized String poll() {
        return failed ? null : frames.pollFirst();
    }

    synchronized boolean isEmpty() {
        return frames.isEmpty();
    }

    synchronized int size() {
        return frames.size();
    }

    synchronized boolean isFailed() {
        return failed;
    }
}
