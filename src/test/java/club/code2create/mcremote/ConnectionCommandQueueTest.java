package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionCommandQueueTest {
    @Test
    void byteBudgetBackpressuresEvenWhenCountSlotsRemainAndRecoversAfterRemoval() throws Exception {
        ConnectionCommandQueue queue = new ConnectionCommandQueue(10, 100);
        queue.put("12345"); // 50 estimated bytes.
        queue.put("67890");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> put = executor.submit(() -> { queue.put("third"); return null; });
            assertThrows(TimeoutException.class, () -> put.get(100, TimeUnit.MILLISECONDS));
            assertEquals("12345", queue.removeHead());
            put.get(1, TimeUnit.SECONDS);
            assertEquals("67890", queue.removeHead());
            assertEquals("third", queue.removeHead());
            assertThrows(IllegalArgumentException.class, () -> queue.put("x".repeat(31)));
            queue.put("again");
            assertEquals("again", queue.removeHead());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void preHelloDeadlineAlsoBoundsBlockedQueueWithoutLeakingBytePermits() throws Exception {
        ConnectionCommandQueue queue = new ConnectionCommandQueue(1, 100);
        queue.put("first");
        assertThrows(IllegalStateException.class,
                () -> queue.put("other", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)));
        assertEquals("first", queue.removeHead());
        queue.put("again");
        assertEquals("again", queue.removeHead());
    }

    @Test
    void fullQueueBackpressuresProducerWithoutDroppingEitherCommand() throws Exception {
        ConnectionCommandQueue queue = new ConnectionCommandQueue(1);
        queue.put("first");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch attemptingSecondPut = new CountDownLatch(1);
        try {
            Future<?> secondPut = executor.submit(() -> {
                attemptingSecondPut.countDown();
                queue.put("second");
                return null;
            });
            attemptingSecondPut.await(1, TimeUnit.SECONDS);
            assertThrows(TimeoutException.class, () -> secondPut.get(100, TimeUnit.MILLISECONDS));

            assertEquals("first", queue.removeHead());
            secondPut.get(1, TimeUnit.SECONDS);
            assertEquals("second", queue.removeHead());
            assertTrue(queue.isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retainedHeadCannotBeOvertakenByFollowingFlush() throws InterruptedException {
        ConnectionCommandQueue queue = new ConnectionCommandQueue(2);
        queue.put("deferred-notification");
        queue.put("connection.flush");

        assertEquals("deferred-notification", queue.peek());
        assertEquals("deferred-notification", queue.peek());
        assertEquals("deferred-notification", queue.removeHead());
        assertEquals("connection.flush", queue.peek());
    }
}
