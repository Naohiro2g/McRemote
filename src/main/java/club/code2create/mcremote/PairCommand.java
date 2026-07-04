package club.code2create.mcremote;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

import net.kyori.adventure.text.Component;

/**
 * ゲーム内コマンド {@code /mcremote pair <code>}（wire-format-design §6.5）。
 *
 * <p>クライアントが {@code auth.pairBegin} で受け取った6桁 {@code pair_code} を、当該プレイヤーが
 * ゲーム内で打つ。実行者の UUID が {@link PairingManager} の pending に束縛され、続く
 * {@code auth.pairPoll} が token を受け取れるようになる。UUID 束縛が要件ゆえ **player 専用**
 * （console からは実行不可）。
 */
@NullMarked
public class PairCommand implements CommandExecutor {
    private final PairingManager pairingManager;

    public PairCommand(PairingManager pairingManager) {
        this.pairingManager = pairingManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || !"pair".equalsIgnoreCase(args[0])) {
            sender.sendMessage(Component.text("Usage: /mcremote pair <code>"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "プレイヤーが実行してください（UUID 束縛が必要） / This command must be run by a player."));
            return true;
        }
        if (args.length < 2 || args[1].isBlank()) {
            sender.sendMessage(Component.text("Usage: /mcremote pair <code>"));
            return true;
        }

        String code = args[1].trim();
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
}
