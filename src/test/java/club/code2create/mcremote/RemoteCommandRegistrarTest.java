package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.bukkit.Location;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCommandRegistrarTest {
    /**
     * DECISIONS 2026-08-26-08: these were never ratified past b5 and are dropped from the b6
     * (protocol 23) registry rather than carried forward as dead handlers. b8 reintroduces
     * nearby/pose/remove as one new contract instead of resurrecting this shape.
     */
    private static final List<String> REMOVED_PROTOCOL22_ENTITY_METHODS = List.of(
            "world.getNearbyEntities",
            "entity.getPos", "entity.setPos",
            "entity.getRotation", "entity.setRotation",
            "entity.getPitch", "entity.setPitch",
            "entity.getYaw", "entity.setYaw",
            "entity.remove");

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

    @Test
    void removedProtocol22EntityMethodsAreUnregistered() {
        CommandRegistry registry = new CommandRegistry();
        EventCommands eventCommands = new EventCommands(
                null, new EventRing(8, 8_000, 4_000), 4, 8);
        RemoteCommandRegistrar.registerB5EventCommands(registry, eventCommands);

        for (String name : REMOVED_PROTOCOL22_ENTITY_METHODS) {
            assertNull(registry.get(name), name + " must not be registered in the protocol 23 registry");
            assertTrue(!registry.names().contains(name));
        }
    }

    @Test
    void removedProtocol22EntityMethodsDispatchAsMethodNotFound() {
        CommandRegistry registry = new CommandRegistry();

        for (String name : REMOVED_PROTOCOL22_ENTITY_METHODS) {
            CapturingContext context = new CapturingContext();
            new CommandDispatcher(context, registry).dispatch(new ParsedCommand(
                    name, new String[]{"00000000-0000-0000-0000-000000000000"}, 1, JsonParser.parseString("[]")));

            assertEquals(-32601, context.code, name);
            assertEquals("method_not_found", context.reason, name);
        }
    }

    @Test
    void b7RegistersDirectionQuartetAndOnlyDamageCapableLightningName() {
        CommandRegistry registry = new CommandRegistry();
        LightningRuntimePolicy policy = new LightningRuntimePolicy(20, 20, 2, 20, 8);
        DirectionCommands direction = new DirectionCommands(null, new EntityHandleRegistry(1));
        LightningCommands lightning = new LightningCommands(
                null, new LightningRateAdmission(policy), policy);

        RemoteCommandRegistrar.registerB7Commands(registry, direction, lightning);

        for (String method : List.of(
                "player.getDirection", "player.setDirection",
                "entity.getDirection", "entity.setDirection")) {
            assertNotNull(registry.get(method));
            assertTrue(!registry.get(method).requiresOrigin());
        }
        assertNotNull(registry.get("world.strikeLightning"));
        assertTrue(registry.get("world.strikeLightning").requiresOrigin());
        assertNull(registry.get("world.strikeLightningEffect"));
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
