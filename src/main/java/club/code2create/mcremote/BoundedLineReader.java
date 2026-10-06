package club.code2create.mcremote;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Counts UTF-8 bytes before decoding; preserves LF, CRLF and CR line framing. */
final class BoundedLineReader {
    private final InputStream input;
    private final Socket socket;
    private final PreAuthPolicy policy;
    private final BooleanSupplier helloComplete;
    private final long helloDeadline;
    private final ConnectionLimitStats stats;
    private final AtomicBoolean timeoutReported = new AtomicBoolean();
    private boolean skipLf;
    private int lastTimeout = -1;

    BoundedLineReader(Socket socket, PreAuthPolicy policy, BooleanSupplier helloComplete) throws IOException {
        this(socket, policy, helloComplete, new ConnectionLimitStats());
    }

    BoundedLineReader(Socket socket, PreAuthPolicy policy, BooleanSupplier helloComplete,
                      ConnectionLimitStats stats) throws IOException {
        this.socket = socket;
        this.input = new BufferedInputStream(socket.getInputStream(), 8192);
        this.policy = policy;
        this.helloComplete = helloComplete;
        this.stats = stats;
        helloDeadline = System.nanoTime() + policy.helloSeconds() * 1_000_000_000L;
    }

    boolean helloExpired() {
        boolean expired = !helloComplete.getAsBoolean() && System.nanoTime() - helloDeadline >= 0;
        if (expired) reportTimeout(ConnectionLimitStats.Reason.HELLO_DEADLINE);
        return expired;
    }

    private void reportTimeout(ConnectionLimitStats.Reason reason) {
        if (timeoutReported.compareAndSet(false, true)) stats.rejected(reason);
    }

    String readLine() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            if (!helloComplete.getAsBoolean()) {
                long remaining = helloDeadline - System.nanoTime();
                if (remaining <= 0) {
                    reportTimeout(ConnectionLimitStats.Reason.HELLO_DEADLINE);
                    throw new SocketTimeoutException("hello deadline exceeded");
                }
                timeout((int) Math.max(1, Math.min(Integer.MAX_VALUE,
                        Math.min(policy.idleSeconds() * 1000L, (remaining + 999_999) / 1_000_000))));
            } else {
                timeout(0);
            }
            int value;
            try { value = input.read(); }
            catch (SocketTimeoutException timeout) {
                // hello can complete while this reader is waiting for the next authenticated command.
                if (helloComplete.getAsBoolean()) continue;
                reportTimeout(System.nanoTime() - helloDeadline >= 0
                        ? ConnectionLimitStats.Reason.HELLO_DEADLINE : ConnectionLimitStats.Reason.IDLE_TIMEOUT);
                throw timeout;
            }
            if (skipLf) { skipLf = false; if (value == '\n') continue; }
            if (value == -1) {
                stats.frameBytes(bytes.size());
                return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
            }
            if (value == '\r' || value == '\n') {
                stats.frameBytes(bytes.size());
                skipLf = value == '\r';
                return bytes.toString(StandardCharsets.UTF_8);
            }
            if (bytes.size() >= policy.maxFrameBytes()) {
                stats.frameBytes(bytes.size() + 1);
                stats.rejected(ConnectionLimitStats.Reason.FRAME_BYTES);
                throw new IOException("input frame limit exceeded");
            }
            bytes.write(value);
        }
    }

    private void timeout(int milliseconds) throws IOException {
        if (lastTimeout != milliseconds) { socket.setSoTimeout(milliseconds); lastTimeout = milliseconds; }
    }

    long helloDeadline() { return helloDeadline; }
}
