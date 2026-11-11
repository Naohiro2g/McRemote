package club.code2create.mcremote;

import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Plugin-owned game text. Non-player command senders keep the existing English replies. */
enum PlayerMessage {
    ROOT_USAGE(
            "使い方: /mcr pair <code>（6桁・区切り不要・半角数字） | hud on|off|toggle|legs|target|status | hud toggle-on-swap on|off | credential status|bootstrap|reset CONFIRM-RESET-ALL-CREDENTIALS",
            "Usage: /mcr pair <code> | hud on|off|toggle|legs|target|status | hud toggle-on-swap on|off | credential status|bootstrap|reset CONFIRM-RESET-ALL-CREDENTIALS"),
    HUD_USAGE(
            "/mcr hud on|off|toggle|legs|target|status または /mcr hud toggle-on-swap on|off（toggleは足元／照準の切替）",
            "/mcr hud on|off|toggle|legs|target|status or /mcr hud toggle-on-swap on|off (toggle switches feet/target coordinates)"),
    HUD_DISABLED("このサーバーではHUDが無効です（hud.enabled）。", "HUD is disabled on this server (hud.enabled)."),
    HUD_LEGS("足元の座標", "Coordinates at your feet"),
    HUD_TARGET("照準先のブロック座標", "Coordinates of the targeted block"),
    PAIRED("ペアリング成功。リモコン側の接続が続行されます。", "Paired. The remote client will continue connecting."),
    PAIR_NOT_FOUND("コードが見つかりません: %s", "Unknown pair code: %s"),
    PAIR_EXPIRED("コードの有効期限切れ: %s", "Pair code expired: %s"),
    PAIR_USED("このコードは既に使用済み: %s", "Pair code already used: %s"),
    ADMIN_DENIED("権限がありません: mcremote.admin", "Permission denied: mcremote.admin"),
    CREDENTIAL_STATUS("認証情報の状態: %s / %s / id=%s", "Credential domain: %s / %s / id=%s"),
    CREDENTIAL_BOOTSTRAPPED("認証情報の領域を初期化しました: %s", "Credential domain bootstrapped: %s"),
    CREDENTIAL_RESET_CONFIRM(
            "リセットするとすべての長期認証情報が無効になります。確認するには: /mcremote credential reset CONFIRM-RESET-ALL-CREDENTIALS",
            "Reset invalidates every long-lived credential. Confirm with: /mcremote credential reset CONFIRM-RESET-ALL-CREDENTIALS"),
    CREDENTIAL_RESET(
            "認証情報の領域をリセットしました: %s（旧状態は退避済み。すべてのクライアントで再ペアリングが必要です）",
            "Credential domain reset: %s (old state archived, all clients must pair again)"),
    CREDENTIAL_FAILED("認証情報の操作に失敗し、安全のため停止しました: %s", "Credential operation failed closed: %s"),
    BANNED("このサーバーからアクセスを禁止されています。", "You've been banned from this server!");

    private final String japanese;
    private final String english;

    PlayerMessage(String japanese, String english) {
        this.japanese = japanese;
        this.english = english;
    }

    static boolean isJapanese(Locale locale) {
        return locale != null && "ja".equals(locale.getLanguage());
    }

    String text(CommandSender sender, Object... arguments) {
        String pattern = sender instanceof Player player && isJapanese(player.locale()) ? japanese : english;
        return String.format(Locale.ROOT, pattern, arguments);
    }

    String english() { return english; }
}
