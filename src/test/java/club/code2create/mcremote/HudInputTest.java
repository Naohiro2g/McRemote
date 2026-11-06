package club.code2create.mcremote;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class HudInputTest {
    @Test
    void explicitlyDisabledSwapDoesNotStealEvenSneakingInput() throws Exception {
        CoordinateHud hud = mock(CoordinateHud.class);
        McRemote plugin = plugin(hud, false);
        PlayerSwapHandItemsEvent event = event(true);
        plugin.onHudSwapHand(event);
        verify(event, never()).setCancelled(anyBoolean());
        verify(hud, never()).togglePoint(any());
    }

    @Test
    void hiddenHudKeepsTheOriginalMinecraftOperation() throws Exception {
        CoordinateHud hud = mock(CoordinateHud.class);
        McRemote plugin = plugin(hud, true);
        PlayerSwapHandItemsEvent hidden = event(false);
        when(hud.isVisible(hidden.getPlayer())).thenReturn(false);
        plugin.onHudSwapHand(hidden);
        verify(hidden, never()).setCancelled(anyBoolean());
        verify(hud, never()).togglePoint(any());
    }

    @Test
    void explicitlyEnabledSwapTogglesWithoutCheckingSneakAndCancelsOnlyThatSwap() throws Exception {
        CoordinateHud hud = mock(CoordinateHud.class);
        McRemote plugin = plugin(hud, true);
        PlayerSwapHandItemsEvent event = event(false);
        Player player = event.getPlayer();
        when(hud.isVisible(player)).thenReturn(true);
        plugin.onHudSwapHand(event);
        verify(event).setCancelled(true);
        verify(hud).togglePoint(player);
        verify(player, never()).isSneaking();
    }

    private static McRemote plugin(CoordinateHud hud, boolean enabled) throws Exception {
        McRemote plugin = mock(McRemote.class);
        var display = McRemote.class.getDeclaredField("coordinateHud");
        display.setAccessible(true);
        display.set(plugin, hud);
        when(hud.isVisible(any())).thenReturn(true);
        when(hud.isToggleOnSwapEnabled(any())).thenReturn(enabled);
        doCallRealMethod().when(plugin).onHudSwapHand(any());
        return plugin;
    }

    private static PlayerSwapHandItemsEvent event(boolean sneaking) {
        Player player = mock(Player.class);
        when(player.isSneaking()).thenReturn(sneaking);
        PlayerSwapHandItemsEvent event = mock(PlayerSwapHandItemsEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }
}
