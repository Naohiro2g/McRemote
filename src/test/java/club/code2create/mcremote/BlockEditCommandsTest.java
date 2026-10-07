package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockEditCommandsTest {
    private static final String STONE = "{\"block_id\":\"stone\",\"state\":{}}";

    @Test void roleLimitRejectsEntireCuboidBeforeWorkOrWorldMutation() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(1, bukkit);
            h.commands.handleSetBlocks(JsonParser.parseString("[0,0,0,1,0,0," + STONE + "]"));
            verify(h.session).isWithinBuildBlocks(2L);
            verify(h.session).respondError(-32000, "build_denied", null);
            verify(h.session, never()).admitSetterWork(anyLong());
            verify(h.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }

    @Test void inclusiveBoundaryMutatesAllTargetBlocksAndReturnsNull() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(2, bukkit);
            h.commands.handleSetBlocks(JsonParser.parseString("[1,0,0,0,0,0," + STONE + "]"));
            verify(h.session).admitBulkRequest(2L);
            verify(h.block, times(2)).setBlockData(h.data, false);
            verify(h.session).respondResult(null);
        }
    }

    @Test void zeroAllowanceRejectsSetBlockBeforeWorkOrMutation() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(0, bukkit);
            h.commands.handleSetBlock(JsonParser.parseString("[0,0,0," + STONE + "]"));
            verify(h.session).respondError(-32000, "build_denied", null);
            verify(h.session, never()).admitSetterWork(anyLong());
            verify(h.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }

    @Test void full32768CompletesOverEightDefaultTicks() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(32768, bukkit);
            var params = JsonParser.parseString("[0,0,0,31,31,31," + STONE + "]");
            for (int tick = 0; tick < 7; tick++) {
                assertThrows(CommandDeferredException.class, () -> h.commands.handleSetBlocks(params));
                verify(h.session, never()).respondResult(any());
                h.work.beginTick();
            }
            h.commands.handleSetBlocks(params);
            verify(h.session, times(1)).admitBulkRequest(32768L);
            verify(h.block, times(32768)).setBlockData(h.data, false);
            verify(h.session, times(1)).respondResult(null);
            verify(h.session, never()).respondError(anyInt(), anyString(), any());
        }
    }

    @Test void requestAboveDefaultLimitIsRejectedBeforeMutation() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(40000, bukkit);
            h.commands.handleSetBlocks(JsonParser.parseString("[0,0,0,32768,0,0," + STONE + "]"));
            verify(h.session).respondError(-32000, "work_limit_exceeded", null);
            verify(h.block, never()).setBlockData(any(), anyBoolean());
        }
    }

    @Test void explicit32769AllowanceCompletesWithDefaultTickBudgets() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set("work.per_request", 32769);
            Harness h = harness(32769, bukkit, RuntimePolicy.from(config));
            var params = JsonParser.parseString("[32768,0,0,0,0,0," + STONE + "]");
            for (int tick = 0; tick < 8; tick++) {
                assertThrows(CommandDeferredException.class, () -> h.commands.handleSetBlocks(params));
                h.work.beginTick();
            }
            h.commands.handleSetBlocks(params);
            verify(h.block, times(32769)).setBlockData(h.data, false);
            verify(h.session).respondResult(null);
        }
    }

    @Test void partialJobWaitsWhenBudgetIsGoneAndDoesNotRespondWithRetryAdvice() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(10000, bukkit);
            var params = JsonParser.parseString("[0,0,0,99,0,99," + STONE + "]");
            assertThrows(CommandDeferredException.class, () -> h.commands.handleSetBlocks(params));
            assertThrows(CommandDeferredException.class, () -> h.commands.handleSetBlocks(params));
            verify(h.block, times(4096)).setBlockData(h.data, false);
            verify(h.session, never()).rejectTemporaryBackpressure();
            verify(h.session, never()).respondResult(any());
        }
    }

    @Test void beforeStartZeroBudgetRejectsRequestAndDefersNotificationWithoutMutation() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(5000, bukkit);
            var params = JsonParser.parseString("[0,0,0,4999,0,0," + STONE + "]");
            when(h.session.reserveBulkWork(anyInt())).thenReturn(0);
            h.commands.handleSetBlocks(params);
            verify(h.session).respondError(-32000, "backpressure", null);
            verify(h.block, never()).setBlockData(any(), anyBoolean());
            when(h.session.rejectTemporaryBackpressure()).thenThrow(CommandDeferredException.INSTANCE);
            assertThrows(CommandDeferredException.class, () -> h.commands.handleSetBlocks(params));
            h.work.beginTick();
            when(h.session.reserveBulkWork(anyInt())).thenReturn(4096, 904);
            assertThrows(CommandDeferredException.class, () -> h.commands.handleSetBlocks(params));
            h.commands.handleSetBlocks(params);
            verify(h.block, times(5000)).setBlockData(h.data, false);
            verify(h.session).respondResult(null);
        }
    }

    @Test void paperFailureAfterPartialWorkIsTerminalAndNotInvalidParams() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(5000, bukkit);
            var params = JsonParser.parseString("[0,0,0,4999,0,0," + STONE + "]");
            assertThrows(CommandDeferredException.class, () -> h.commands.handleSetBlocks(params));
            h.work.beginTick();
            doThrow(new IllegalArgumentException("Paper failed")).when(h.block).setBlockData(h.data, false);
            h.commands.handleSetBlocks(params);
            verify(h.session).respondError(-32603, "internal_error", null);
            verify(h.session, never()).respondResult(any());
        }
    }

    @Test void disconnectAndWorldLossStopPendingWork() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(5000, bukkit);
            var params = JsonParser.parseString("[0,0,0,4999,0,0," + STONE + "]");
            assertThrows(CommandDeferredException.class, () -> h.commands.handleSetBlocks(params));
            h.session.pendingRemoval = true;
            h.work.beginTick();
            h.commands.handleSetBlocks(params);
            verify(h.block, times(4096)).setBlockData(h.data, false);
            verify(h.session, never()).respondResult(any());
            Harness other = harness(5000, bukkit);
            assertThrows(CommandDeferredException.class, () -> other.commands.handleSetBlocks(params));
            other.work.beginTick();
            bukkit.when(() -> Bukkit.getWorld(other.world.getUID())).thenReturn(null);
            other.commands.handleSetBlocks(params);
            verify(other.session).respondError(-32603, "internal_error", null);
        }
    }

    @Test void full32768CuboidExecutesWhenRoleAndTickBudgetsAllowIt() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set("work.per_session_tick", 32768);
            config.set("work.per_player_tick", 32768);
            Harness h = harness(32768, bukkit, RuntimePolicy.from(config));
            h.commands.handleSetBlocks(JsonParser.parseString("[0,0,0,31,31,31," + STONE + "]"));
            verify(h.session).admitBulkRequest(32768L);
            verify(h.block, times(32768)).setBlockData(h.data, false);
            verify(h.session).respondResult(null);
            verify(h.session, never()).respondError(anyInt(), anyString(), any());
        }
    }

    private static Harness harness(int maxBlocks, org.mockito.MockedStatic<Bukkit> bukkit) {
        return harness(maxBlocks, bukkit, RuntimePolicy.from(new MemoryConfiguration()));
    }

    private static Harness harness(int maxBlocks, org.mockito.MockedStatic<Bukkit> bukkit, RuntimePolicy policy) {
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
        when(plugin.getCatalogService()).thenReturn(catalog);
        RemoteSession session = mock(RemoteSession.class);
        when(session.getPlugin()).thenReturn(plugin);
        when(session.getOrigin()).thenReturn(new Location(world, 200, 0, 200));
        when(session.hasConstructionPermission()).thenReturn(true);
        when(session.isWithinBuildRange(any())).thenReturn(true);
        ConstructionPermissions snapshot = new ConstructionPermissions(true, true, 1000, maxBlocks);
        when(session.isWithinBuildBlocks(anyLong())).thenAnswer(call -> snapshot.allowsBlockCount(call.getArgument(0, Long.class)));
        WorkAdmission work = new WorkAdmission(policy);
        UUID epoch = UUID.randomUUID();
        when(session.admitSetterWork(anyLong())).thenAnswer(call -> {
            WorkAdmission.Result result = work.admit(epoch, null, call.getArgument(0, Long.class).intValue());
            if (result == WorkAdmission.Result.ACCEPTED) return true;
            session.respondError(-32000, result == WorkAdmission.Result.WORK_LIMIT_EXCEEDED
                    ? "work_limit_exceeded" : "backpressure", null);
            return false;
        });
        when(session.admitBulkRequest(anyLong())).thenAnswer(call -> {
            if (work.permitsRequest(call.getArgument(0, Long.class))) return true;
            session.respondError(-32000, "work_limit_exceeded", null);
            return false;
        });
        when(session.reserveBulkWork(anyInt())).thenAnswer(call -> work.reserve(epoch, null, call.getArgument(0)));
        when(session.rejectTemporaryBackpressure()).thenAnswer(call -> {
            session.respondError(-32000, "backpressure", null);
            return false;
        });
        return new Harness(session, world, block, data, new BlockEditCommands(session, new MiscCommands(session)), work);
    }

    private record Harness(RemoteSession session, World world, Block block, BlockData data,
                           BlockEditCommands commands, WorkAdmission work) {}
}
