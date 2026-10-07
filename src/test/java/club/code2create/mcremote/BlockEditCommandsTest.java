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
            verify(h.session).admitSetterWork(2L);
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

    @Test void largerRoleAllowanceDoesNotRaiseExistingTickBudget() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Harness h = harness(32768, bukkit);
            h.commands.handleSetBlocks(JsonParser.parseString("[0,0,0,31,31,31," + STONE + "]"));
            verify(h.session).isWithinBuildBlocks(32768L);
            verify(h.session).admitSetterWork(32768L);
            verify(h.session).respondError(-32000, "backpressure", null);
            verify(h.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }

    @Test void full32768CuboidExecutesWhenRoleAndTickBudgetsAllowIt() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set("work.per_session_tick", 32768);
            config.set("work.per_player_tick", 32768);
            Harness h = harness(32768, bukkit, RuntimePolicy.from(config));
            h.commands.handleSetBlocks(JsonParser.parseString("[0,0,0,31,31,31," + STONE + "]"));
            verify(h.session).admitSetterWork(32768L);
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
        return new Harness(session, world, block, data, new BlockEditCommands(session, new MiscCommands(session)));
    }

    private record Harness(RemoteSession session, World world, Block block, BlockData data,
                           BlockEditCommands commands) {}
}
