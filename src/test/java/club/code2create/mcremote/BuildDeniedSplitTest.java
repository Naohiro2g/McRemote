package club.code2create.mcremote;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Stream;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildDeniedSplitTest {
    static Stream<Arguments> cases() {
        return Stream.of("setBlock", "setBlocks", "getBlock", "getBlocks", "setSign", "updateSignLine", "getHeight")
                .flatMap(method -> Stream.of("range", "world").flatMap(kind -> Stream.of(false,true)
                    .map(notification -> Arguments.of(method, kind, notification, 0))));
    }

    static Stream<Arguments> heights() {
        return Stream.of("getBlocks","setSign","updateSignLine").flatMap(m -> Stream.of(-65,320).flatMap(y ->
                Stream.of(false,true).map(n -> Arguments.of(m,"world",n,y))));
    }
    static Stream<Arguments> heightIgnoresY() { return Stream.of(false,true).map(n -> Arguments.of("getHeight","range",n,1000)); }

    @ParameterizedTest @MethodSource({"cases", "heights", "heightIgnoresY"})
    void reasonDataAndSilentNotificationFollowTheSameGuardOrder(String method, String kind, boolean notification, int y) throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            WorldBoundsTest.unrestricted(world);
            WorldBorder border = world.getWorldBorder();
            when(border.getCenter()).thenReturn(new Location(world,200,0,200));
            when(border.getSize()).thenReturn(100d);
            when(border.isInside(any())).thenAnswer(c -> {
                Location p = c.getArgument(0);
                return p.getX() >= 150 && p.getX() < 250 && p.getZ() >= 150 && p.getZ() < 250;
            });
            int x = kind.equals("world") ? (y == 0 ? 51 : 0) : 11;
            var session = session(world, notification, 10, 0);
            if (method.equals("getHeight")) put(session,"origin",new Location(world,200,y,200));
            var catalog = mock(CatalogService.class);
            when(session.getPlugin().getCatalogService()).thenReturn(catalog);
            when(catalog.getBlockStateValues("minecraft:stone")).thenReturn(Map.of());
            bukkit.when(() -> Bukkit.createBlockData("minecraft:stone")).thenReturn(mock(BlockData.class));
            String spec = ",{\"block_id\":\"stone\",\"state\":{}}";
            String params = switch(method) {
                case "setBlock" -> "["+x+","+y+",0"+spec+"]";
                case "setBlocks" -> "["+(x-1)+","+(y==320?319:y)+",0,"+x+","+y+",0"+spec+"]";
                case "getBlocks" -> "["+(x-1)+","+(y==320?319:y)+",0,"+x+","+y+",0]";
                case "setSign" -> "["+x+","+y+",0,{\"front\":[\"\",\"\",\"\",\"\"]}]";
                case "updateSignLine" -> "["+x+","+y+",0,\"front\",0,\"text\"]";
                case "getHeight" -> "["+x+",0]";
                default -> "["+x+","+y+",0]";
            };
            invoke(method, session, JsonParser.parseString(params));
            String reason = kind.equals("world") ? "out_of_bounds" : "build_range_exceeded";
            var captured = ArgumentCaptor.forClass(Map.class);
            verify(session).respondError(eq(-32000),eq(reason),captured.capture());
            var data = new Gson().toJsonTree(captured.getValue()).getAsJsonObject();
            // For a two-point region the first point may already be outside.
            int firstX = (method.equals("setBlocks") || method.equals("getBlocks")) && kind.equals("world") && y != 320 ? x-1 : x;
            assertEquals(200+firstX,data.getAsJsonObject("violating").get("x").getAsInt());
            assertEquals(y,data.getAsJsonObject("violating").get("y").getAsInt());
            if (kind.equals("range")) {
                assertEquals(JsonParser.parseString("{\"center\":{\"x\":200.0,\"z\":200.0},\"build_range\":10}"),data.get("bounds"));
            } else {
                assertEquals(319,data.getAsJsonObject("bounds").get("y_max").getAsInt());
                verify(session,never()).hasConstructionPermission();
            }
            assertEquals(notification, queue(session).isEmpty());
            if (!notification) {
                var response=JsonParser.parseString(queue(session).poll()).getAsJsonObject();
                assertEquals(reason,response.getAsJsonObject("error").getAsJsonObject("data").get("reason").getAsString());
            }
            verify(world,never()).getBlockAt(any(Location.class));
            verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        }
    }

    static Stream<Arguments> counts() { return Stream.of(false,true).flatMap(n -> Stream.of(false,true).map(b -> Arguments.of(n,b))); }
    @ParameterizedTest @MethodSource("counts")
    void blockCountIncludesLimitAndRequestedBeforeAnyMutation(boolean notification, boolean bulk) throws Exception {
        try (var bukkit=mockStatic(Bukkit.class)) {
            World world=mock(World.class); WorldBoundsTest.unrestricted(world);
            var session=session(world,notification,10,bulk?1:0);
            var catalog=mock(CatalogService.class);when(session.getPlugin().getCatalogService()).thenReturn(catalog);
            when(catalog.getBlockStateValues("minecraft:stone")).thenReturn(Map.of());
            bukkit.when(() -> Bukkit.createBlockData("minecraft:stone")).thenReturn(mock(BlockData.class));
            invoke(bulk?"setBlocks":"setBlock",session,JsonParser.parseString(bulk
                ? "[0,0,0,1,0,0,{\"block_id\":\"stone\",\"state\":{}}]"
                : "[0,0,0,{\"block_id\":\"stone\",\"state\":{}}]"));
            verify(session).respondError(-32000,"block_count_limit_exceeded",Map.of("limit",bulk?1:0,"requested",bulk?2L:1L));
            assertEquals(notification,queue(session).isEmpty());
            verify(world,never()).getBlockAt(any(Location.class));
            verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fractionalViolationKeepsAllCoordinates(boolean notification) throws Exception {
        World world=mock(World.class);
        var session=session(world,notification,10,0);
        assertFalse(BuildPolicyErrors.range(session,new Location(world,210.25,7.75,200.125)));
        verify(session).respondError(-32000,"build_range_exceeded",Map.of(
                "bounds",Map.of("center",Map.of("x",200d,"z",200d),"build_range",10),
                "violating",Map.of("x",210.25,"y",7.75,"z",200.125)));
        assertEquals(notification,queue(session).isEmpty());
    }

    private static RemoteSession session(World world, boolean notification,int range,int limit) throws Exception {
        var session=mock(RemoteSession.class,CALLS_REAL_METHODS);
        put(session,"plugin",mock(McRemote.class));put(session,"origin",new Location(world,200,0,200));
        put(session,"boundUuid",UUID.randomUUID());put(session,"constructionPermissions",new ConstructionPermissions(true,true,range,limit));
        put(session,"outQueue",new ConnectionFrameQueue(10));put(session,"queueLock",new Object());put(session,"activeId",notification?null:7);
        doReturn(true).when(session).hasConstructionPermission();
        return session;
    }
    private static void invoke(String method,RemoteSession s,JsonElement p) {
        var misc=new MiscCommands(s);
        switch(method) {
            case "setBlock" -> new BlockEditCommands(s,misc).handleSetBlock(p);
            case "setBlocks" -> new BlockEditCommands(s,misc).handleSetBlocks(p);
            case "getBlock" -> new BlockQueryCommands(s,misc).handleGetBlock(p);
            case "getBlocks" -> new BlockQueryCommands(s,misc).handleGetBlocks(p);
            case "setSign" -> new SignCommands(s,misc).handleSetSign(p);
            case "updateSignLine" -> new SignCommands(s,misc).handleUpdateSignLine(p);
            case "getHeight" -> new WorldB5Commands(s,new EntityHandleRegistry(16),RuntimePolicy.from(new org.bukkit.configuration.MemoryConfiguration())).handleGetHeight(p);
            default -> throw new AssertionError(method);
        }
    }
    private static void put(Object o,String name,Object value) throws Exception {Field f=RemoteSession.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static ConnectionFrameQueue queue(RemoteSession s) throws Exception {Field f=RemoteSession.class.getDeclaredField("outQueue");f.setAccessible(true);return (ConnectionFrameQueue)f.get(s);}
}
