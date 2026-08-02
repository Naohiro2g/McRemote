package club.code2create.mcremote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jspecify.annotations.NullMarked;

import net.kyori.adventure.text.Component;

/**
 * ゲーム内コマンド {@code /mcremote pair <code>}（wire-format-design §6.5）。
 *
 * <p>クライアントが {@code auth.pairBegin} で受け取った6桁 {@code pair_code} を、当該プレイヤーが
 * ゲーム内で打つ。実行者の UUID が {@link PairingManager} の pending に束縛され、続く
 * {@code auth.pairPoll} が token を受け取れるようになる。UUID 束縛が要件ゆえ **player 専用**
 * （console からは実行不可）。
 *
 * <p>表示は {@code 333-333} のダッシュ区切りで、コピーしたコマンド全体をチャットへ貼り付ける
 * 運用（NOTES 2026-07-05 (b)）。よって引数は {@link #normalizeCode} で素6桁 ASCII へ正規化してから
 * 照合する＝ダッシュ・空白・全角数字を human 入力の一点で吸収する。
 */
@NullMarked
public class PairCommand implements TabExecutor {
    private static final String USAGE =
            "Usage: /mcremote pair <code> | credential status|bootstrap|reset CONFIRM-RESET-ALL-CREDENTIALS";

    private final McRemote plugin;
    private final PairingManager pairingManager;
    private final CredentialService credentialService;

    public PairCommand(McRemote plugin, PairingManager pairingManager,
                       CredentialService credentialService) {
        this.plugin = plugin;
        this.pairingManager = pairingManager;
        this.credentialService = credentialService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && "credential".equalsIgnoreCase(args[0])) {
            return handleCredentialAdmin(sender, args);
        }
        if (args.length < 1 || !"pair".equalsIgnoreCase(args[0])) {
            sender.sendMessage(Component.text(USAGE));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "プレイヤーが実行してください（UUID 束縛が必要） / This command must be run by a player."));
            return true;
        }
        if (args.length < 2 || args[1].isBlank()) {
            sender.sendMessage(Component.text(USAGE));
            return true;
        }

        String code = normalizeCode(args[1]);
        if (code.length() != 6) {
            sender.sendMessage(Component.text(USAGE));
            return true;
        }
        PairingManager.BindStatus status = pairingManager.bind(code, player.getUniqueId());
        Component message = switch (status) {
            case OK -> Component.text(
                    "ペアリング成功 / Paired. リモコン側の接続が続行されます。");
            case NOT_FOUND -> Component.text(
                    "コードが見つかりません / Unknown pair code: " + code);
            case EXPIRED -> Component.text(
                    "コードの有効期限切れ / Pair code expired: " + code);
            case ALREADY_BOUND -> Component.text(
                    "このコードは既に使用済み / Pair code already used: " + code);
        };
        player.sendMessage(message);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> roots = sender.hasPermission("mcremote.admin")
                    ? List.of("pair", "credential") : List.of("pair");
            return StringUtil.copyPartialMatches(args[0], roots, new ArrayList<>());
        }
        if (args.length == 2 && "credential".equalsIgnoreCase(args[0])
                && sender.hasPermission("mcremote.admin")) {
            return StringUtil.copyPartialMatches(
                    args[1], List.of("status", "bootstrap", "reset"), new ArrayList<>());
        }
        // コード欄（args[1]）：秘密ゆえ提案しない＋既定のプレイヤー名補完を抑止するため空を返す。
        return Collections.emptyList();
    }

    private boolean handleCredentialAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mcremote.admin")) {
            sender.sendMessage(Component.text("Permission denied: mcremote.admin"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(USAGE));
            return true;
        }
        try {
            switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
                case "status" -> sender.sendMessage(Component.text(
                        "Credential domain: " + credentialService.health() + " / "
                                + credentialService.healthDetail() + " / id="
                                + credentialService.credentialDomainId()));
                case "bootstrap" -> {
                    java.util.UUID domain = credentialService.bootstrap();
                    sender.sendMessage(Component.text(
                            "Credential domain bootstrapped: " + domain));
                }
                case "reset" -> {
                    if (args.length != 3
                            || !"CONFIRM-RESET-ALL-CREDENTIALS".equals(args[2])) {
                        sender.sendMessage(Component.text(
                                "Reset invalidates every long-lived credential. Confirm with: "
                                        + "/mcremote credential reset CONFIRM-RESET-ALL-CREDENTIALS"));
                        return true;
                    }
                    CredentialService.ResetResult result = credentialService.reset();
                    plugin.closeAllLongLivedSessions();
                    sender.sendMessage(Component.text(
                            "Credential domain reset: " + result.credentialDomainId()
                                    + " (old state archived, all clients must pair again)"));
                }
                default -> sender.sendMessage(Component.text(USAGE));
            }
        } catch (Exception e) {
            sender.sendMessage(Component.text(
                    "Credential operation failed closed: " + e.getMessage()));
        }
        return true;
    }

    /**
     * {@code /mcremote pair} 引数を照合キー（素6桁 ASCII）へ正規化する。
     *
     * <p>ASCII 数字以外（'-'・空白等）を捨てて素6桁に帰着させる＝{@code 333-333} 表示のコマンドを
     * そのまま貼り付けても素6桁 {@code 333333} でも同一キーになる。全角数字は非対応（意図的除外・
     * NOTES 2026-07-06：{@code /mcremote pair } まで打ってから全角へ切り替える経路は実運用外と判断）。
     * 長さ検証は呼び出し側で行う。
     */
    private static String normalizeCode(String raw) {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
