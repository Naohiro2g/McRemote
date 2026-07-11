package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.logging.Logger;

public class MiscCommands {
    private static final Logger logger = Logger.getLogger("McR_Misc"); // Logger for logging messages

    private final RemoteSession session;
    private static final boolean debug = false; // Debug flag

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
        Location origin = session.getOrigin();
        int x = (int) Double.parseDouble(xstr);
        int y = (int) Double.parseDouble(ystr);
        int z = (int) Double.parseDouble(zstr);
        return new Location(origin.getWorld(), origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
    }

    public void handleGetHeight(World world, String[] args) {
        Location loc = parseRelativeBlockLocation(args[0], "0", args[1]);
        int height = world.getHighestBlockYAt(loc);
        session.send(String.valueOf(height));
    }

    public void handleSpawnParticle(String[] args) {
        Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
        float offsetX = Float.parseFloat(args[3]);
        float offsetY = Float.parseFloat(args[4]);
        float offsetZ = Float.parseFloat(args[5]);
        Particle particleType = org.bukkit.Particle.valueOf(args[6].toUpperCase());
        double speed = Double.parseDouble(args[7]);
        int count = Integer.parseInt(args[8]);
        boolean force;
        if (args.length > 9) {
            force = Boolean.parseBoolean(args[9]);
        } else {
            force = true;
        }
        try {
            session.getOrigin().getWorld().spawnParticle(particleType, loc, count, offsetX, offsetY, offsetZ, speed, null, force);
            if (debug) {
                session.send("Particle spawn debug: " + particleType + " at " + loc);
            }
        } catch (Exception e) {
            session.send("Error: " + e.getMessage());
            logger.warning("Error: " + e.getMessage());
        }
    }

    public void handleSpawnEntity(String[] args) {
        Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(args[3].toUpperCase());
        } catch (Exception exc) {
            entityType = EntityType.valueOf("COW");
        }
        Entity entity = session.getOrigin().getWorld().spawnEntity(loc, entityType);
        session.send(entity.getUniqueId().toString());
    }

}
