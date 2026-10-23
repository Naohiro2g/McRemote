package club.code2create.mcremote;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/** Self-only game command; it does not select other players or alter remote build contexts. */
final class HudCommand {
    private static final List<String> ACTIONS = List.of("on", "off", "toggle", "body", "target", "status");
    private static final String USAGE = "/mcremote hud on|off|toggle|body|target|status（toggleは足元／照準の切替）";

    private HudCommand() {}

    static boolean execute(CoordinateHud hud, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("HUDはゲーム内のプレイヤーが操作してください。"));
            return true;
        }
        if (hud == null) {
            sender.sendMessage(Component.text("このサーバーではHUDが無効です（hud.enabled）。"));
            return true;
        }
        if (args.length != 2 || !ACTIONS.contains(args[1].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(Component.text(USAGE));
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> hud.setVisible(player, true);
            case "off" -> hud.setVisible(player, false);
            case "toggle" -> hud.togglePoint(player);
            case "body" -> hud.setPoint(player, HudSampler.Point.BODY);
            case "target" -> hud.setPoint(player, HudSampler.Point.TARGET);
            case "status" -> { }
            default -> throw new IllegalStateException("validated action");
        }
        sender.sendMessage(Component.text(hud.status(player)));
        return true;
    }

    static List<String> complete(String[] args) {
        return args.length == 2 ? StringUtil.copyPartialMatches(args[1], ACTIONS, new ArrayList<>()) : List.of();
    }
}
