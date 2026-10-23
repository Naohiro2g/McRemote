package club.code2create.mcremote;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HudCommandTest {
    @Test
    void mcremoteRoutesHudCommandsWithoutEnteringPairingOrCredentialOperations() {
        PairingManager pairing = mock(PairingManager.class);
        CredentialService credentials = mock(CredentialService.class);
        CoordinateHud hud = mock(CoordinateHud.class);
        Player player = mock(Player.class);
        when(hud.status(player)).thenReturn("HUD: OFF");
        PairCommand root = new PairCommand(mock(McRemote.class), pairing, credentials, hud);
        root.onCommand(player, mock(org.bukkit.command.Command.class), "mcremote", new String[]{"hud", "off"});
        verify(hud).setVisible(player, false);
        verifyNoInteractions(pairing, credentials);
        assertTrue(root.onTabComplete(player, null, "mcremote", new String[]{"h"}).contains("hud"));
    }

    @Test
    void malformedAndConsoleCommandsDoNotMutateState() {
        CoordinateHud hud = mock(CoordinateHud.class);
        Player player = mock(Player.class);
        HudCommand.execute(hud, player, new String[]{"hud", "off", "extra"});
        HudCommand.execute(hud, player, new String[]{"hud", "other"});
        HudCommand.execute(hud, mock(CommandSender.class), new String[]{"hud", "on"});
        verifyNoInteractions(hud);
        verify(player, times(2)).sendMessage(any(Component.class));
    }

    @Test
    void toggleChangesTheTargetPointRatherThanVisibilityAndStatusDoesNotChangeIt() {
        CoordinateHud hud = mock(CoordinateHud.class);
        Player player = mock(Player.class);
        when(hud.status(player)).thenReturn("HUD: ON / 足元の座標");
        HudCommand.execute(hud, player, new String[]{"hud", "toggle"});
        verify(hud).togglePoint(player);
        verify(hud, never()).setVisible(any(), anyBoolean());
        clearInvocations(hud);
        HudCommand.execute(hud, player, new String[]{"hud", "status"});
        verify(hud).status(player);
        verifyNoMoreInteractions(hud);
    }

    @Test
    void commandsControlOnlyTheIssuingPlayerAndOfferSubcommands() {
        CoordinateHud hud = mock(CoordinateHud.class);
        Player player = mock(Player.class);
        when(hud.status(player)).thenReturn("HUD");
        HudCommand.execute(hud, player, new String[]{"hud", "off"});
        verify(hud).setVisible(player, false);
        HudCommand.execute(hud, player, new String[]{"hud", "target"});
        verify(hud).setPoint(player, HudSampler.Point.TARGET);
        assertEquals(java.util.List.of("target"), HudCommand.complete(new String[]{"hud", "ta"}));
    }
}
