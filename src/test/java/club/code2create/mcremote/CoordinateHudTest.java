package club.code2create.mcremote;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoordinateHudTest {
    @Test
    void originChangesAndDisconnectFallbackUseOnlyLiveAuthenticatedConnectionsForThisPlayer() {
        World world = mock(World.class);
        Player player = player(world, 222, 80, 233);
        RemoteSession first = session(player, world, 200, 0, 200);
        RemoteSession second = session(player, world, 100, 10, 100);
        RemoteSession foreign = session(player(mock(World.class), 0, 0, 0), world, 0, 0, 0);
        RemoteSession pending = session(player, world, 0, 0, 0);
        when(pending.isHelloComplete()).thenReturn(false);
        List<RemoteSession> active = new ArrayList<>(List.of(foreign, pending, first, second));
        HudDisplay display = mock(HudDisplay.class);
        CoordinateHud hud = new CoordinateHud(ignored -> active, display);
        hud.tick(List.of(player));
        ArgumentCaptor<HudFrame> frames = ArgumentCaptor.forClass(HudFrame.class);
        verify(display).update(eq(player), frames.capture());
        assertEquals(new HudFrame.Coordinates(22, 80, 33), frames.getValue().relative());
        when(first.getOrigin()).thenReturn(new Location(world, 210, 5, 220));
        hud.tick(List.of(player));
        verify(display, times(2)).update(eq(player), frames.capture());
        assertEquals(new HudFrame.Coordinates(12, 75, 13), frames.getValue().relative());
        first.pendingRemoval = true;
        hud.tick(List.of(player));
        verify(display, times(3)).update(eq(player), frames.capture());
        assertEquals(new HudFrame.Coordinates(122, 70, 133), frames.getValue().relative());
        active.clear();
        hud.tick(List.of(player));
        verify(display, times(4)).update(eq(player), frames.capture());
        assertNull(frames.getValue().relative());
        assertEquals(new HudFrame.Coordinates(222, 80, 233), frames.getValue().absolute());
    }

    @Test
    void offStaysOffAcrossDisconnectAndKeyStateStaysWithThePlayerAcrossConnectionChanges() {
        World world = mock(World.class);
        Player player = player(world, 0, 64, 0);
        List<RemoteSession> active = new ArrayList<>(List.of(session(player, world, 200, 0, 200)));
        HudDisplay display = mock(HudDisplay.class);
        CoordinateHud hud = new CoordinateHud(ignored -> active, display);
        hud.setPoint(player, HudSampler.Point.TARGET);
        verify(player).rayTraceBlocks(8, org.bukkit.FluidCollisionMode.NEVER);
        hud.setVisible(player, false);
        clearInvocations(display, player);
        active.clear();
        hud.tick(List.of(player));
        assertFalse(hud.isVisible(player));
        verify(display, never()).update(any(), any());
        verify(player, never()).rayTraceBlocks(anyDouble(), any());
        hud.setVisible(player, true);
        verify(player).rayTraceBlocks(8, org.bukkit.FluidCollisionMode.NEVER);
        assertTrue(hud.status(player).contains("照準先"));
    }

    @Test
    void playerQuitAndPluginStopRemoveOnlyOwnedBars() {
        World world = mock(World.class);
        Player first = player(world, 1, 64, 1), second = player(world, 2, 64, 2);
        HudDisplay display = mock(HudDisplay.class);
        CoordinateHud hud = new CoordinateHud(ignored -> List.of(), display);
        hud.tick(List.of(first, second));
        hud.playerQuit(first);
        verify(display).hide(first.getUniqueId());
        hud.close();
        verify(display).close();
    }

    @Test
    void offlinePlayerIsCleanedEvenIfQuitEventWasMissed() {
        Player player = player(mock(World.class), 0, 64, 0);
        HudDisplay display = mock(HudDisplay.class);
        CoordinateHud hud = new CoordinateHud(ignored -> List.of(), display);
        hud.tick(List.of(player));
        hud.tick(List.of());
        verify(display).hide(player.getUniqueId());
    }

    private static Player player(World world, int x, int y, int z) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, x, y, z));
        return player;
    }

    private static RemoteSession session(Player player, World world, int x, int y, int z) {
        UUID playerId = player.getUniqueId();
        RemoteSession session = mock(RemoteSession.class);
        when(session.getConnectionEpoch()).thenReturn(UUID.randomUUID());
        when(session.getBoundUuid()).thenReturn(playerId);
        when(session.isHelloComplete()).thenReturn(true);
        when(session.getOrigin()).thenReturn(new Location(world, x, y, z));
        return session;
    }
}
