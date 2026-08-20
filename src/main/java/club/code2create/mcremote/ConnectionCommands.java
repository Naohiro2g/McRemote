package club.code2create.mcremote;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.Map;

/** Commands scoped to one successfully negotiated connection epoch. */
final class ConnectionCommands {
    private final RemoteSession session;

    ConnectionCommands(RemoteSession session) {
        this.session = session;
    }

    void handleFlush(JsonElement params) {
        try {
            WireParams.positional(params, 0);
            // Reaching this FIFO item means every earlier item is terminal on the Paper main thread.
            // respondResult is intentionally a no-op for an id-less call, which offers no guarantee.
            session.respondResult(null);
        } catch (IllegalArgumentException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", "params");
            session.respondError(-32602, "invalid_params", data);
        }
    }
}
