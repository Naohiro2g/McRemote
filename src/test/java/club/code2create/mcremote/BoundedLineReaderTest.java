package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class BoundedLineReaderTest {
    @Test void framingUtf8AndExactByteBoundaryUseActualSockets() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket server = listener.accept()) {
            BoundedLineReader reader = new BoundedLineReader(server, PreAuthAdmissionTest.policy(2, 1, 2), () -> true);
            client.getOutputStream().write("あいう\n".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, reader::readLine); // 9 UTF-8 bytes exceeds 8.
        }
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket server = listener.accept()) {
            BoundedLineReader reader = new BoundedLineReader(server, PreAuthAdmissionTest.policy(2, 1, 2), () -> true);
            client.getOutputStream().write("12345678\r\nあい\rX\n".getBytes(StandardCharsets.UTF_8));
            client.shutdownOutput();
            assertEquals("12345678", reader.readLine());
            assertEquals("あい", reader.readLine());
            assertEquals("X", reader.readLine());
            assertNull(reader.readLine());
        }
    }

    @Test void idlePeerTimesOutAndHelloTransitionDoesNotDisconnectAuthenticatedPeer() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket server = listener.accept()) {
            AtomicBoolean authenticated = new AtomicBoolean();
            BoundedLineReader reader = new BoundedLineReader(server, PreAuthAdmissionTest.policy(2, 1, 2), authenticated::get);
            assertThrows(SocketTimeoutException.class, reader::readLine);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> next = executor.submit(reader::readLine);
                authenticated.set(true);
                assertThrows(TimeoutException.class, () -> next.get(1200, TimeUnit.MILLISECONDS));
                client.getOutputStream().write("ok\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("ok", next.get(1, TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void slowInputCannotExtendAbsoluteHelloDeadline() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket server = listener.accept()) {
            BoundedLineReader reader = new BoundedLineReader(server, PreAuthAdmissionTest.policy(2, 1, 2), () -> false);
            ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
            try {
                sender.scheduleAtFixedRate(() -> {
                    try { client.getOutputStream().write('x'); } catch (IOException ignored) {}
                }, 0, 400, TimeUnit.MILLISECONDS);
                long started = System.nanoTime();
                assertThrows(SocketTimeoutException.class, reader::readLine);
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4));
                assertTrue(reader.helloExpired());
            } finally { sender.shutdownNow(); }
        }
    }
}
