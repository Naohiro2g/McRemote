package club.code2create.mcremote;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HudCommandTest {
    @Test
    void mcrIsRegisteredAsAnAliasOfTheExistingCommand() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(java.util.List.of("mcr"), config.getStringList("commands.mcremote.aliases"));
        }
    }

    @Test
    void legsAndPersonalSwapCommandsWorkThroughTheShortCommandName() {
        CoordinateHud hud = mock(CoordinateHud.class);
        Player player = mock(Player.class);
        when(hud.status(player)).thenReturn("HUD");
        PairingManager pairing = mock(PairingManager.class);
        CredentialService credentials = mock(CredentialService.class);
        PairCommand root = new PairCommand(mock(McRemote.class), pairing, credentials, hud);
        root.onCommand(player, mock(org.bukkit.command.Command.class), "mcr", new String[]{"hud", "legs"});
        verify(hud).setPoint(player, HudSampler.Point.LEGS);
        root.onCommand(player, mock(org.bukkit.command.Command.class), "mcr", new String[]{"hud", "toggle-on-swap", "ON"});
        verify(hud).setToggleOnSwap(player, true);
        root.onCommand(player, mock(org.bukkit.command.Command.class), "mcr", new String[]{"hud", "toggle-on-swap", "off"});
        verify(hud).setToggleOnSwap(player, false);
        verifyNoInteractions(pairing, credentials);
        assertEquals(java.util.List.of("legs"), HudCommand.complete(new String[]{"hud", "le"}));
        assertEquals(java.util.List.of("on", "off"), HudCommand.complete(new String[]{"hud", "toggle-on-swap", "o"}));
    }

    @Test
    void removedBodyCommandAndInvalidSwapArgumentsDoNotMutatePlayerSettings() {
        CoordinateHud hud = mock(CoordinateHud.class);
        Player player = mock(Player.class);
        for (String[] args : java.util.List.of(new String[]{"hud", "body"},
                new String[]{"hud", "toggle-on-swap"}, new String[]{"hud", "toggle-on-swap", "yes"},
                new String[]{"hud", "toggle-on-swap", "on", "extra"})) {
            HudCommand.execute(hud, player, args);
        }
        verifyNoInteractions(hud);
    }

    @Test
    void mcremoteRoutesHudCommandsWithoutEnteringPairingOrCredentialOperations() {
        PairingManager pairing = mock(PairingManager.class);
        CredentialService credentials = mock(CredentialService.class);
        CoordinateHud hud = mock(CoordinateHud.class);
        Player player = mock(Player.class);
        when(hud.status(player)).thenReturn("HUD: off");
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
        when(hud.status(player)).thenReturn("HUD: on / 足元の座標");
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
