package club.code2create.mcremote;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded, thread-safe FIFO between a connection's socket reader and the Paper main thread.
 * A full queue blocks the reader so TCP backpressure is applied instead of dropping notifications.
 */
final class ConnectionCommandQueue {
    private final ArrayBlockingQueue<String> commands;

    ConnectionCommandQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.commands = new ArrayBlockingQueue<>(capacity, true);
    }

    void put(String command) throws InterruptedException {
        commands.put(Objects.requireNonNull(command, "command"));
    }

    String peek() {
        return commands.peek();
    }

    String removeHead() {
        return commands.poll();
    }

    boolean isEmpty() {
        return commands.isEmpty();
    }

    int size() {
        return commands.size();
    }
}
