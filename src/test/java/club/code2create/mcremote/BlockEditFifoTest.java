package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the production FIFO/dispatcher, including restoration of the original request id. */
class BlockEditFifoTest {
    @Test void requestsAndNotificationsHoldFollowingGetterAndFlushUntilAllBlocksFinish() throws Exception {
        for (boolean notification : new boolean[]{false, true}) {
            try (var bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                UUID worldId = UUID.randomUUID();
                when(world.getUID()).thenReturn(worldId);
                bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
                Block block = mock(Block.class);
                BlockData data = mock(BlockData.class);
                when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
                bukkit.when(() -> Bukkit.createBlockData("minecraft:stone")).thenReturn(data);
                CatalogService catalog = mock(CatalogService.class);
                when(catalog.getBlockStateValues("minecraft:stone")).thenReturn(Map.of());
                McRemote plugin = mock(McRemote.class);
                WorkAdmission work = new WorkAdmission(RuntimePolicy.from(new MemoryConfiguration()));
                when(plugin.getWorkAdmission()).thenReturn(work);
                when(plugin.getCatalogService()).thenReturn(catalog);
                RemoteSession session = mock(RemoteSession.class, CALLS_REAL_METHODS);
                var queue = new ConnectionCommandQueue(8);
                set(session, "plugin", plugin);
                set(session, "connectionEpoch", UUID.randomUUID());
                set(session, "origin", new Location(world, 200, 0, 200));
                set(session, "inQueue", queue);
                set(session, "in", mock(BoundedLineReader.class));
                set(session, "running", true);
                set(session, "helloComplete", true);
                set(session, "authCommands", mock(AuthCommands.class));
                set(session, "commandParser", new CommandParser());
                doReturn(true).when(session).hasConstructionPermission();
                doReturn(true).when(session).isWithinBuildRange(any());
                doReturn(true).when(session).isWithinBuildBlocks(anyLong());
                var responses = new ArrayList<Integer>();
                doAnswer(call -> {
                    Integer id = (Integer) get(session, "activeId");
                    if (id != null) responses.add(id);
                    return null;
                }).when(session).respondResult(any());
                doAnswer(call -> { fail("unexpected command error " + call.getArgument(1)); return null; })
                        .when(session).respondError(anyInt(), anyString(), any());
                var registry = new CommandRegistry();
                var commands = new BlockCommands(session, new MiscCommands(session));
                commands.register(registry);
                var visited = new ArrayList<String>();
                doAnswer(call -> { visited.add(call.getArgument(0) + "," + call.getArgument(1) + "," + call.getArgument(2)); return block; })
                        .when(world).getBlockAt(anyInt(), anyInt(), anyInt());
                registry.registerStructured("test.observe", params -> {
                    assertEquals(5000, visited.size());
                    session.respondResult("complete");
                });
                registry.registerStructured("connection.flush", new ConnectionCommands(session)::handleFlush, false);
                set(session, "blockCommands", commands);
                set(session, "commandDispatcher", new CommandDispatcher(session, registry));
                queue.put("{\"jsonrpc\":\"2.0\"," + (notification ? "" : "\"id\":1,")
                        + "\"method\":\"world.setBlocks\",\"params\":[24,9,19,0,0,0,{\"block_id\":\"stone\",\"state\":{}}]}");
                queue.put("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"test.observe\",\"params\":[]}");
                queue.put("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"connection.flush\",\"params\":[]}");
                session.tick();
                assertEquals(4096, visited.size());
                assertTrue(responses.isEmpty());
                assertEquals(3, queue.size());
                work.beginTick();
                session.tick();
                assertEquals(notification ? List.of(2, 3) : List.of(1, 2, 3), responses);
                assertTrue(queue.isEmpty());
                assertEquals(5000, new java.util.HashSet<>(visited).size());
                assertEquals("200,9,200", visited.getFirst());
                assertEquals("224,0,219", visited.getLast());
            }
        }
    }

    @Test void notificationPressureIsCountedButRequestPressureIsNot() throws Exception {
        var stats = new ConnectionLimitStats();
        var admission = new PreAuthAdmission(PreAuthPolicy.forMaxPlayers(16, 120), stats);
        RemoteSession session = mock(RemoteSession.class, CALLS_REAL_METHODS);
        set(session, "admission", admission.acquire());
        stats.drainSummary();
        set(session, "activeId", 1);
        session.recordParticleWorkBackpressure();
        assertNull(stats.drainSummary());
        set(session, "activeId", null);
        session.recordParticleWorkBackpressure();
        assertTrue(stats.drainSummary().contains("PARTICLE_NOTIFICATION_WORK_BACKPRESSURE=1"));
        assertNull(stats.drainSummary());
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = RemoteSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object get(Object object, String name) throws Exception {
        var field = RemoteSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
