package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import net.kyori.adventure.text.Component;

import java.math.BigDecimal;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
import java.util.logging.Logger;

public class MiscCommands {
    private static final Logger logger = Logger.getLogger("McR_Misc"); // Logger for logging messages

    private final RemoteSession session;
    public MiscCommands(RemoteSession session) {
        this.session = session;
    }

    /**
     * chat.post(msg) — チャットへ送信（wire-format-design §4 表、params=[msg]）。
     * 既定は send-only（notification）。id 付き要求にのみ ack を、msg 欠落時にのみ
     * §5 error（-32602 invalid_params）を同期応答する（DECISIONS 2026-06-27-04）。
     * notification では respondResult/respondError とも no-op。
     */
    public void handleChatPost(String[] args) {
        if (args.length < 1 || args[0].isEmpty()) {
            session.respondError(-32602, "invalid_params", null);
            logger.warning("chat.post requires a message.");
            return;
        }
        String message = args[0];
        Bukkit.broadcast(Component.text(message));
        session.respondResult(message);
    }

    Location parseRelativeBlockLocation(String xstr, String ystr, String zstr) {
        int x = parseIntegralCoordinate(xstr);
        int y = parseIntegralCoordinate(ystr);
        int z = parseIntegralCoordinate(zstr);
        return parseRelativeBlockLocation(x, y, z);
    }

    Location parseRelativeBlockLocation(int x, int y, int z) {
        Location origin = session.getOrigin();
        return new Location(origin.getWorld(), origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
    }

    static OptionalInt findHighestExposedBlockY(
            int worldMinY,
            int worldMaxY,
            int requestedMaxY,
            IntPredicate isPassableAtY
    ) {
        int startY = Math.min(requestedMaxY, worldMaxY);
        if (startY < worldMinY || worldMaxY < worldMinY) {
            return OptionalInt.empty();
        }

        boolean aboveIsPassable = startY == worldMaxY || isPassableAtY.test(startY + 1);
        for (int y = startY; y >= worldMinY; y--) {
            boolean currentIsPassable = isPassableAtY.test(y);
            if (!currentIsPassable && aboveIsPassable) {
                return OptionalInt.of(y);
            }
            aboveIsPassable = currentIsPassable;
        }
        return OptionalInt.empty();
    }

    private static int parseIntegralCoordinate(String raw) {
        try {
            return new BigDecimal(raw).toBigIntegerExact().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new NumberFormatException("coordinate must be an integer");
        }
    }
}
