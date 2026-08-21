package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.bukkit.Location;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoteCommandRegistrarTest {
    @Test
    void b5RegistersPollButLeavesClearUnreachable() {
        CommandRegistry registry = new CommandRegistry();
        EventCommands eventCommands = new EventCommands(
                null, new EventRing(8, 8_000, 4_000), 4, 8);

        RemoteCommandRegistrar.registerB5EventCommands(registry, eventCommands);

        assertNotNull(registry.get("events.poll"));
        assertNull(registry.get("events.clear"));
    }

    @Test
    void eventsClearDispatchesAsMethodNotFound() {
        CommandRegistry registry = new CommandRegistry();
        EventCommands eventCommands = new EventCommands(
                null, new EventRing(8, 8_000, 4_000), 4, 8);
        RemoteCommandRegistrar.registerB5EventCommands(registry, eventCommands);
        CapturingContext context = new CapturingContext();

        new CommandDispatcher(context, registry).dispatch(new ParsedCommand(
                "events.clear", new String[0], 1, JsonParser.parseString("[]")));

        assertEquals(-32601, context.code);
        assertEquals("method_not_found", context.reason);
    }

    private static final class CapturingContext implements CommandDispatchContext {
        private int code;
        private String reason;

        @Override
        public Location getOrigin() {
            return null;
        }

        @Override
        public void respondError(int code, String reason, Map<String, Object> extraData) {
            this.code = code;
            this.reason = reason;
        }

        @Override
        public void close() {
            throw new AssertionError("method_not_found must not close the connection");
        }
    }
}
