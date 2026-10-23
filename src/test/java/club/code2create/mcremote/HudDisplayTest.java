package club.code2create.mcremote;

import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HudDisplayTest {
    @Test
    void repeatedUpdatesKeepOneBossBarAndMutateItsTextInPlace() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        HudDisplay display = new HudDisplay();
        HudFrame first = HudFrame.sample(null, null, 100);
        display.update(player, first);
        display.update(player, first);
        ArgumentCaptor<BossBar> bar = ArgumentCaptor.forClass(BossBar.class);
        verify(player).showBossBar(bar.capture());
        assertEquals(first.render(), bar.getValue().name());
        HudFrame next = HudFrame.sample(null, null, 101);
        display.update(player, next);
        assertEquals(next.render(), bar.getValue().name());
        verify(player, times(1)).showBossBar(any(BossBar.class));
        display.hide(player.getUniqueId());
        display.close();
        verify(player).hideBossBar(bar.getValue());
    }

    @Test
    void reconnectingPlayerObjectCannotLeaveTheOldBarAttached() {
        UUID id = UUID.randomUUID();
        Player previous = mock(Player.class), current = mock(Player.class);
        when(previous.getUniqueId()).thenReturn(id);
        when(current.getUniqueId()).thenReturn(id);
        HudDisplay display = new HudDisplay();
        HudFrame frame = HudFrame.sample(null, null, 0);
        display.update(previous, frame);
        display.update(current, frame);
        verify(previous).hideBossBar(any(BossBar.class));
        verify(current).showBossBar(any(BossBar.class));
        display.close();
        verify(current).hideBossBar(any(BossBar.class));
    }
}
