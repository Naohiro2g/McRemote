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
