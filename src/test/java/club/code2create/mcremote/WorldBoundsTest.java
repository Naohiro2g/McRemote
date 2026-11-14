package club.code2create.mcremote;

import com.google.gson.JsonParser;
import java.util.Map;
import java.util.stream.Stream;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldBoundsTest {
    static void unrestricted(World world) {
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        WorldBorder border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.getCenter()).thenReturn(new Location(world, 0, 0, 0));
        when(border.getSize()).thenReturn(60_000_000d);
        when(border.isInside(any())).thenReturn(true);
    }

    static Stream<Arguments> outside() {
        return Stream.of("setBlock", "setBlocks", "getBlock").flatMap(method ->
                Stream.of(new int[]{0,-65,0}, new int[]{0,320,0}, new int[]{51,0,0}, new int[]{0,0,-51}, new int[]{0,1001,0}, new int[]{Integer.MAX_VALUE,0,0})
                    .flatMap(point -> Stream.of(false,true).map(notification -> Arguments.of(method, point, notification))));
    }

    static Stream<Arguments> inside() {
        return Stream.of("setBlock", "setBlocks", "getBlock").flatMap(method ->
                Stream.of(new int[]{0,-64,0}, new int[]{0,319,0}, new int[]{49,0,0}, new int[]{0,0,-50})
                    .map(point -> Arguments.of(method, point, false)));
    }

    @ParameterizedTest @MethodSource({"outside", "inside"})
    void rejectsBeforePolicyWorkAndBlockAccess(String method, int[] point, boolean notification) {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            unrestricted(world);
            WorldBorder border = world.getWorldBorder();
            when(border.getCenter()).thenReturn(new Location(world, 200, 0, 200));
            when(border.getSize()).thenReturn(100d);
            when(border.isInside(any())).thenAnswer(c -> {
                Location p = c.getArgument(0);
                return p.getX() >= 150 && p.getX() < 250 && p.getZ() >= 150 && p.getZ() < 250;
            });
            BlockData data = mock(BlockData.class);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:stone")).thenReturn(data);
            CatalogService catalog = mock(CatalogService.class);
            when(catalog.getBlockStateValues("minecraft:stone")).thenReturn(Map.of());
            McRemote plugin = mock(McRemote.class);
            when(plugin.getCatalogService()).thenReturn(catalog);
            RemoteSession session = mock(RemoteSession.class);
            when(session.getPlugin()).thenReturn(plugin);
            when(session.getOrigin()).thenReturn(new Location(world, 200, 0, 200));
            // A conflicting policy refusal must lose to world bounds.
            when(session.hasConstructionPermission()).thenReturn(true);
            when(session.isWithinBuildRange(any())).thenReturn(false);
            if (notification) doCallRealMethod().when(session).respondError(anyInt(), anyString(), any());
            String xyz = point[0]+","+point[1]+","+point[2];
            String spec = ", {\"block_id\":\"stone\",\"state\":{}}";
            if (method.equals("getBlock")) new BlockQueryCommands(session,new MiscCommands(session))
                    .handleGetBlock(JsonParser.parseString("["+xyz+"]"));
            else if (method.equals("setBlock")) new BlockEditCommands(session,new MiscCommands(session))
                    .handleSetBlock(JsonParser.parseString("["+xyz+spec+"]"));
            else new BlockEditCommands(session,new MiscCommands(session))
                    .handleSetBlocks(JsonParser.parseString("[0,0,0,"+xyz+spec+"]"));
            var captured = org.mockito.ArgumentCaptor.forClass(Map.class);
            boolean outside = point[1] < -64 || point[1] > 319 || point[0] > 49 || point[2] < -50;
            if (!outside) {
                verify(session).respondError(-32000,"build_denied",null);
                return;
            }
            verify(session).respondError(eq(-32000),eq("out_of_bounds"),captured.capture());
            Map<?,?> details = captured.getValue();
            assertEquals(Map.of("x",200L+point[0],"y",(long)point[1],"z",200L+point[2]),details.get("violating"));
            Map<?,?> bounds = (Map<?,?>) details.get("bounds");
            assertEquals(-64,bounds.get("y_min"));
            assertEquals(319,bounds.get("y_max"));
            assertEquals(Map.of("center",Map.of("x",200d,"z",200d),"size",100d),bounds.get("world_border"));
            verify(session,never()).isWithinBuildRange(any());
            verify(session,never()).admitSetterWork(anyLong());
            verify(session,never()).admitBulkRequest(anyLong());
            verify(world,never()).getBlockAt(any(Location.class));
            verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
            verify(session,never()).respondResult(any());
        }
    }
}
