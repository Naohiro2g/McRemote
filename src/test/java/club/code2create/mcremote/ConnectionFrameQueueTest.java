package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFrameQueueTest {
    @Test
    void preservesFrameOrderIncludingFlushResponse() {
        ConnectionFrameQueue queue = new ConnectionFrameQueue(2);
        assertTrue(queue.offer("prior-response"));
        assertTrue(queue.offer("flush-response"));

        assertEquals("prior-response", queue.poll());
        assertEquals("flush-response", queue.poll());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFailed());
    }

    @Test
    void saturationIsTerminalAndCannotExposeFalseFlushSuccess() {
        int capacity = B5RuntimePolicy.DEFAULT_CONNECTION_RESPONSE_QUEUE_CAPACITY;
        ConnectionFrameQueue queue = new ConnectionFrameQueue(capacity);
        for (int i = 0; i < capacity; i++) {
            assertTrue(queue.offer("prior-" + i));
        }
        assertEquals(capacity, queue.size());

        assertFalse(queue.offer("flush-response"));
        assertTrue(queue.isFailed());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertNull(queue.poll());
        assertFalse(queue.offer("later-response"));
    }
}
