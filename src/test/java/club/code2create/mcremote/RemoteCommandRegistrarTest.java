package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

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
}
