package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

//import java.util.logging.Logger;

public class MiscCommands {
//    private static final Logger logger = Logger.getLogger("McR_Misc"); // Logger for logging messages

    private final RemoteSession session;

    public MiscCommands(RemoteSession session) {
        this.session = session;
    }

    public void handleChatPost(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            sb.append(arg).append(" ");
        }
        Bukkit.broadcast(Component.text(sb.toString().trim()));
    }

    // private Location parseRelativeBlockLocation(String x, String y, String z) {
    // return new Location(null, Double.parseDouble(x), Double.parseDouble(y),
    // Double.parseDouble(z));
    // }

    Location parseRelativeBlockLocation(String xstr, String ystr, String zstr) {
        Location origin = session.getOrigin();
        int x = (int) Double.parseDouble(xstr);
        int y = (int) Double.parseDouble(ystr);
        int z = (int) Double.parseDouble(zstr);
        return new Location(origin.getWorld(), origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
    }

    public Location parseRelativeLocation(String xstr, String ystr, String zstr) {
        Location origin = session.getOrigin();
        double x = Double.parseDouble(xstr);
        double y = Double.parseDouble(ystr);
        double z = Double.parseDouble(zstr);
        return new Location(origin.getWorld(), origin.getX() + x, origin.getY() + y, origin.getZ() + z);
    }

//    public Location parseRelativeBlockLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
//        Location loc = parseRelativeBlockLocation(xstr, ystr, zstr);
//        loc.setPitch(pitch);
//        loc.setYaw(yaw);
//        return loc;
//    }
//
//    public Location parseRelativeLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
//        Location loc = parseRelativeLocation(xstr, ystr, zstr);
//        loc.setPitch(pitch);
//        loc.setYaw(yaw);
//        return loc;
//    }
//
//    public String blockLocationToRelative(Location loc) {
//        Location origin = session.getOrigin();
//        return (loc.getBlockX() - origin.getBlockX()) + "," + (loc.getBlockY() - origin.getBlockY()) + "," +
//                (loc.getBlockZ() - origin.getBlockZ());
//    }
//
//    public String locationToRelative(Location loc) {
//        Location origin = session.getOrigin();
//        return (loc.getX() - origin.getX()) + "," + (loc.getY() - origin.getY()) + "," +
//                (loc.getZ() - origin.getZ());
//    }



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
            session.send("Particle spawn successful");
        } catch (Exception e) {
            session.send("Error: " + e.getMessage());
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
