package club.code2create.mcremote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerMessageLanguageTest {
    static Stream<Locale> locales() {
        return Stream.of(Locale.JAPAN, Locale.ENGLISH, Locale.FRENCH, Locale.ROOT, null);
    }

    @ParameterizedTest @MethodSource("locales")
    void hudUsageDisabledAndStatusFollowThePlayerLocale(Locale locale) {
        Player player = player(locale);
        HudCommand.execute(null, player, new String[]{"hud", "status"});
        assertLanguage(lastMessage(player), locale);
        clearInvocations(player);
        var hud = new CoordinateHud(ignored -> List.of(), mock(HudDisplay.class), true);
        HudCommand.execute(hud, player, new String[]{"hud", "unknown"});
        assertLanguage(lastMessage(player), locale);
        clearInvocations(player);
        HudCommand.execute(hud, player, new String[]{"hud", "status"});
        assertEquals(japanese(locale) ? "HUD: on / 足元の座標 / toggle-on-swap: on"
                : "HUD: on / Coordinates at your feet / toggle-on-swap: on", lastMessage(player));
        clearInvocations(player);
        HudCommand.execute(hud, player, new String[]{"hud", "target"});
        assertEquals(japanese(locale) ? "HUD: on / 照準先のブロック座標 / toggle-on-swap: on"
                : "HUD: on / Coordinates of the targeted block / toggle-on-swap: on", lastMessage(player));
    }

    @ParameterizedTest @MethodSource("locales")
    void pairingRepliesUseOneLanguageWithoutChangingTheBinding(Locale locale) {
        for (var status : PairingManager.BindStatus.values()) {
            Player player = player(locale);
            PairingManager pairing = mock(PairingManager.class);
            when(pairing.bind("123456", player.getUniqueId())).thenReturn(status);
            PairCommand command = new PairCommand(mock(McRemote.class), pairing, mock(CredentialService.class));
            command.onCommand(player, mock(Command.class), "mcr", new String[]{"pair", "123-456"});
            assertLanguage(lastMessage(player), locale);
            if (status == PairingManager.BindStatus.OK) assertEquals(japanese(locale)
                    ? "ペアリング成功。リモコン側の接続が続行されます。"
                    : "Paired. The remote client will continue connecting.", lastMessage(player));
            else assertTrue(lastMessage(player).endsWith("123456"));
            verify(pairing).bind("123456", player.getUniqueId());
        }
    }

    @ParameterizedTest @MethodSource("locales")
    void rootHelpAndCredentialRepliesFollowThePlayerLocale(Locale locale) throws Exception {
        Player player = player(locale);
        var credentials = mock(CredentialService.class);
        when(credentials.health()).thenReturn(CredentialService.Health.HEALTHY);
        when(credentials.healthDetail()).thenReturn("healthy");
        UUID domain = UUID.randomUUID();
        when(credentials.credentialDomainId()).thenReturn(domain);
        when(credentials.bootstrap()).thenReturn(domain);
        when(credentials.reset()).thenReturn(new CredentialService.ResetResult(domain, null, null));
        var plugin = mock(McRemote.class);
        var command = new PairCommand(plugin, mock(PairingManager.class), credentials);
        for (String[] args : List.of(new String[]{}, new String[]{"pair"},
                new String[]{"credential", "status"})) {
            clearInvocations(player);
            command.onCommand(player, mock(Command.class), "mcr", args);
            assertLanguage(lastMessage(player), locale);
        }
        when(player.hasPermission("mcremote.admin")).thenReturn(true);
        for (String[] args : List.of(new String[]{"credential", "status"},
                new String[]{"credential", "bootstrap"}, new String[]{"credential", "reset"},
                new String[]{"credential", "reset", "CONFIRM-RESET-ALL-CREDENTIALS"})) {
            clearInvocations(player);
            command.onCommand(player, mock(Command.class), "mcr", args);
            assertLanguage(lastMessage(player), locale);
        }
        verify(plugin).closeAllLongLivedSessions();
        when(credentials.bootstrap()).thenThrow(new java.io.IOException("synthetic failure"));
        clearInvocations(player);
        command.onCommand(player, mock(Command.class), "mcr", new String[]{"credential", "bootstrap"});
        assertLanguage(lastMessage(player), locale);
        assertTrue(lastMessage(player).endsWith("synthetic failure"));
    }

    @Test void languageChangesDuringTheSameSessionTakeEffectOnTheNextReply() {
        Player player = player(Locale.JAPAN);
        var hud = new CoordinateHud(ignored -> List.of(), mock(HudDisplay.class), true);
        assertTrue(hud.status(player).contains("足元"));
        when(player.locale()).thenReturn(Locale.ENGLISH);
        assertEquals("HUD: on / Coordinates at your feet / toggle-on-swap: on", hud.status(player));
    }

    @ParameterizedTest @MethodSource("locales")
    void pluginOwnedKickTextUsesThePlayerLocaleAndKeepsTheLogReasonEnglish(Locale locale) {
        assertEquals(japanese(locale) ? "このサーバーからアクセスを禁止されています。"
                : "You've been banned from this server!", PlayerMessage.BANNED.text(player(locale)));
        assertEquals("You've been banned from this server!", PlayerMessage.BANNED.english());
    }

    @Test void consoleRepliesKeepTheirExistingText() {
        CommandSender console = mock(CommandSender.class);
        var command = new PairCommand(mock(McRemote.class), mock(PairingManager.class), mock(CredentialService.class));
        command.onCommand(console, mock(Command.class), "mcr", new String[]{"pair", "123456"});
        assertEquals("プレイヤーが実行してください（UUID 束縛が必要） / This command must be run by a player.",
                lastMessage(console));
        clearInvocations(console);
        command.onCommand(console, mock(Command.class), "mcr", new String[]{"hud", "status"});
        assertEquals("HUDはゲーム内のプレイヤーが操作してください。", lastMessage(console));
        clearInvocations(console);
        command.onCommand(console, mock(Command.class), "mcr", new String[]{"credential", "status"});
        assertEquals("Permission denied: mcremote.admin", lastMessage(console));
        clearInvocations(console);
        command.onCommand(console, mock(Command.class), "mcr", new String[]{});
        assertEquals("Usage: /mcr pair <code> | hud on|off|toggle|legs|target|status | hud toggle-on-swap on|off | credential status|bootstrap|reset CONFIRM-RESET-ALL-CREDENTIALS",
                lastMessage(console));
    }

    private static Player player(Locale locale) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        return player;
    }

    private static String lastMessage(CommandSender sender) {
        var captured = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(captured.capture());
        return PlainTextComponentSerializer.plainText().serialize(captured.getValue());
    }

    private static boolean japanese(Locale locale) { return locale != null && "ja".equals(locale.getLanguage()); }

    private static void assertLanguage(String message, Locale locale) {
        boolean hasJapanese = message.codePoints().anyMatch(c -> (c >= 0x3040 && c <= 0x30ff)
                || (c >= 0x4e00 && c <= 0x9fff));
        assertEquals(japanese(locale), hasJapanese, "player locale=" + locale + ": " + message);
    }
}
