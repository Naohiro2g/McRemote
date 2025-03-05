package org.wensheng.juicyraspberrypie;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.logging.Logger;

public class MiscCommands {
    private static final Logger logger = Logger.getLogger("MCR_Misc"); // Logger for logging messages

    private RemoteSession session;

    public MiscCommands(RemoteSession session) {
        this.session = session;
    }

    public void handleGetNearbyEntities(World world, String[] args) {
        Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
        double nearby_distance = 10.0;
        if (args.length > 3) {
            nearby_distance = Double.parseDouble(args[3]);
        }
        Collection<Entity> nearbyEntities = world.getNearbyEntities(loc, nearby_distance, 5.0, nearby_distance);
        StringBuilder sb = new StringBuilder();
        for (Entity e : nearbyEntities) {
            sb.append(e.getName()).append(":").append(e.getUniqueId()).append(",");
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1); // Remove trailing comma
        }
        session.send(sb.toString());
    }

    public void handleGetHeight(World world, String[] args) {
        Location loc = parseRelativeBlockLocation(args[0], "0", args[1]);
        int height = world.getHighestBlockYAt(loc);
        session.send(String.valueOf(height));
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

    public Location parseRelativeBlockLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
        Location loc = parseRelativeBlockLocation(xstr, ystr, zstr);
        loc.setPitch(pitch);
        loc.setYaw(yaw);
        return loc;
    }

    public Location parseRelativeLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
        Location loc = parseRelativeLocation(xstr, ystr, zstr);
        loc.setPitch(pitch);
        loc.setYaw(yaw);
        return loc;
    }

    public String blockLocationToRelative(Location loc) {
        Location origin = session.getOrigin();
        return (loc.getBlockX() - origin.getBlockX()) + "," + (loc.getBlockY() - origin.getBlockY()) + "," +
                (loc.getBlockZ() - origin.getBlockZ());
    }

    public String locationToRelative(Location loc) {
        Location origin = session.getOrigin();
        return (loc.getX() - origin.getX()) + "," + (loc.getY() - origin.getY()) + "," +
                (loc.getZ() - origin.getZ());
    }

}
