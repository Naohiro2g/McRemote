package club.code2create.mcremote;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HudSamplerTest {
    @Test
    void bodyUsesPlayerFeetAndCurrentWorldTimeWithoutRayTracing() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, -0.1, 70, 22.9));
        when(world.getTime()).thenReturn(23999L, 0L);
        Location origin = new Location(world, 200, 5, 200);
        HudFrame frame = HudSampler.sample(player, origin, HudSampler.Point.BODY);
        assertEquals(new HudFrame.Coordinates(-1, 70, 22), frame.absolute());
        assertEquals(new HudFrame.Coordinates(-201, 65, -178), frame.relative());
        assertEquals(23999, frame.timeTick());
        assertEquals(0, HudSampler.sample(player, origin, HudSampler.Point.BODY).timeTick());
        verify(player, never()).rayTraceBlocks(anyDouble(), any(FluidCollisionMode.class));
    }

    @Test
    void targetUsesTheHitBlockRatherThanAnAdjacentPlacementPointOrTheExactHitPosition() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        Block block = mock(Block.class);
        when(player.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, 4, 65, -2));
        when(player.rayTraceBlocks(8, FluidCollisionMode.NEVER))
                .thenReturn(new RayTraceResult(new Vector(4.9, 65.5, -1), block, org.bukkit.block.BlockFace.SOUTH));
        HudFrame frame = HudSampler.sample(player, new Location(world, 0, 5, 0), HudSampler.Point.TARGET);
        assertEquals(new HudFrame.Coordinates(4, 65, -2), frame.absolute());
        assertEquals(new HudFrame.Coordinates(4, 60, -2), frame.relative());
        assertEquals(HudSampler.Point.TARGET, frame.point());
        assertEquals(0xd8d6d5, frame.render().color().value());
    }

    @Test
    void losingTheTargetDoesNotReuseThePreviousTarget() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        Block block = mock(Block.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getTime()).thenReturn(100L);
        when(block.getLocation()).thenReturn(new Location(world, 4, 65, -2));
        when(player.rayTraceBlocks(8, FluidCollisionMode.NEVER))
                .thenReturn(new RayTraceResult(new Vector(4, 65, -2), block, null), (RayTraceResult) null);
        assertNotNull(HudSampler.sample(player, null, HudSampler.Point.TARGET).absolute());
        HudFrame missing = HudSampler.sample(player, null, HudSampler.Point.TARGET);
        assertNull(missing.absolute());
        assertNull(missing.relative());
        assertEquals(100, missing.timeTick());
    }
}
