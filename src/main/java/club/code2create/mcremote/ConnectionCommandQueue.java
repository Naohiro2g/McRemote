package club.code2create.mcremote;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, thread-safe FIFO between a connection's socket reader and the Paper main thread.
 * A full queue blocks the reader so TCP backpressure is applied instead of dropping notifications.
 */
final class ConnectionCommandQueue {
    private final ArrayBlockingQueue<String> commands;
    private final Semaphore bytes;
    private final int byteCapacity;

    ConnectionCommandQueue(int capacity) {
        this(capacity, Integer.MAX_VALUE);
    }

    ConnectionCommandQueue(int capacity, int byteCapacity) {
        if (capacity <= 0 || byteCapacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.commands = new ArrayBlockingQueue<>(capacity, true);
        this.byteCapacity = byteCapacity;
        this.bytes = new Semaphore(byteCapacity, true);
    }

    void put(String command) throws InterruptedException {
        put(command, null);
    }

    void put(String command, Long deadline) throws InterruptedException {
        if (deadline != null && System.nanoTime() - deadline >= 0)
            throw new IllegalStateException("hello deadline exceeded while queueing");
        int weight = weight(Objects.requireNonNull(command, "command"));
        if (weight > byteCapacity) throw new IllegalArgumentException("command exceeds queue byte budget");
        if (deadline == null) bytes.acquire(weight);
        else if (!bytes.tryAcquire(weight, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
            throw new IllegalStateException("hello deadline exceeded while queueing");
        boolean accepted = false;
        try {
            if (deadline == null) { commands.put(command); accepted = true; }
            else accepted = commands.offer(command, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!accepted) throw new IllegalStateException("hello deadline exceeded while queueing");
        } finally {
            if (!accepted) bytes.release(weight);
        }
    }

    String peek() {
        return commands.peek();
    }

    String removeHead() {
        String command = commands.poll();
        if (command != null) bytes.release(weight(command));
        return command;
    }

    private static int weight(String command) { return Math.addExact(40, Math.multiplyExact(2, command.length())); }

    boolean isEmpty() {
        return commands.isEmpty();
    }

    int size() {
        return commands.size();
    }
}
