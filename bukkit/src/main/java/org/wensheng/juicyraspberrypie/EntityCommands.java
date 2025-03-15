package org.wensheng.juicyraspberrypie;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.UUID;
import java.util.logging.Logger;

public class EntityCommands {
    private static final Logger logger = Logger.getLogger("MCR_Entity"); // Logger for logging messages

    private final RemoteSession session;
    private final MiscCommands miscCommands;

    public EntityCommands(RemoteSession session, MiscCommands miscCommands) {
        this.session = session;
        this.miscCommands = miscCommands;
    }

    public void handleEntityCommand(String c, String[] args) {
        Entity entity = Bukkit.getEntity(UUID.fromString(args[0]));
        if (entity == null) {
            session.send("Entity not found");
            logger.warning("Entity not found: " + args[0]);
            return;
        }

        switch (c) {
            case "entity.getPos":
                handleEntityGetPos(entity);
                break;
            case "entity.setPos":
                handleEntitySetPos(entity, args);
                break;
            case "entity.getRotation":
                handleEntityGetRotation(entity);
                break;
            case "entity.setRotation":
                handleEntitySetRotation(entity, args);
                break;
            case "entity.getPitch":
                handleEntityGetPitch(entity);
                break;
            case "entity.setPitch":
                handleEntitySetPitch(entity, args);
                break;
            case "entity.getYaw":
                handleEntityGetYaw(entity);
                break;
            case "entity.setYaw":
                handleEntitySetYaw(entity, args);
                break;
            case "entity.remove":
                handleEntityRemove(entity);
                break;
            default:
                session.send("Unknown entity command: " + c);
                break;
        }
    }

    private void handleEntityGetPos(Entity entity) {
        Location loc = entity.getLocation();
        session.send(loc.getX() + "," + loc.getY() + "," + loc.getZ());
    }

    private void handleEntitySetPos(Entity entity, String[] args) {
        try {
            Location loc = miscCommands.parseRelativeBlockLocation(args[1], args[2], args[3]);
            entity.teleport(loc);
            session.send("Entity position set to: " + loc.getX() + "," + loc.getY() + "," + loc.getZ());
        } catch (Exception e) {
            session.send("Error setting entity position: " + e.getMessage());
            logger.warning("Error setting entity position: " + e.getMessage());
        }
    }

    private void handleEntityGetRotation(Entity entity) {
        Location loc = entity.getLocation();
        session.send(loc.getYaw() + "," + loc.getPitch());
    }

    private void handleEntitySetRotation(Entity entity, String[] args) {
        try {
            Location loc = entity.getLocation();
            loc.setYaw(Float.parseFloat(args[1]));
            loc.setPitch(Float.parseFloat(args[2]));
            entity.teleport(loc);
            session.send("Entity rotation set to: " + loc.getYaw() + "," + loc.getPitch());
        } catch (Exception e) {
            session.send("Error setting entity rotation: " + e.getMessage());
            logger.warning("Error setting entity rotation: " + e.getMessage());
        }
    }

    private void handleEntityGetPitch(Entity entity) {
        session.send(String.valueOf(entity.getLocation().getPitch()));
    }

    private void handleEntitySetPitch(Entity entity, String[] args) {
        try {
            Location loc = entity.getLocation();
            loc.setPitch(Float.parseFloat(args[1]));
            entity.teleport(loc);
            session.send("Entity pitch set to: " + loc.getPitch());
        } catch (Exception e) {
            session.send("Error setting entity pitch: " + e.getMessage());
            logger.warning("Error setting entity pitch: " + e.getMessage());
        }
    }

    private void handleEntityGetYaw(Entity entity) {
        session.send(String.valueOf(entity.getLocation().getYaw()));
    }

    private void handleEntitySetYaw(Entity entity, String[] args) {
        try {
            Location loc = entity.getLocation();
            loc.setYaw(Float.parseFloat(args[1]));
            entity.teleport(loc);
            session.send("Entity yaw set to: " + loc.getYaw());
        } catch (Exception e) {
            session.send("Error setting entity yaw: " + e.getMessage());
            logger.warning("Error setting entity yaw: " + e.getMessage());
        }
    }

    private void handleEntityRemove(Entity entity) {
        try {
            entity.remove();
            session.send("Entity removed successfully.");
        } catch (Exception e) {
            session.send("Error removing entity: " + e.getMessage());
            logger.warning("Error removing entity: " + e.getMessage());
        }
    }
}
