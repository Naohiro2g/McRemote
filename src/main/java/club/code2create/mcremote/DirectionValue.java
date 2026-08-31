package club.code2create.mcremote;

import com.google.gson.JsonArray;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.math.BigDecimal;
import java.util.List;

/** Scale-safe protocol 23.1 direction normalization and Paper rotation mapping. */
final class DirectionValue {
    private DirectionValue() {
    }

    static Unit parse(JsonArray args, int offset) {
        return normalize(
                WireParams.finiteDouble(args, offset),
                WireParams.finiteDouble(args, offset + 1),
                WireParams.finiteDouble(args, offset + 2));
    }

    static Unit read(Entity entity) {
        Location location = entity.getLocation();
        Vector direction = location.getDirection();
        return normalize(direction.getX(), direction.getY(), direction.getZ());
    }

    static Unit normalize(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("direction components must be finite");
        }
        double scale = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (scale == 0.0) {
            throw new ZeroDirectionException();
        }
        double scaledX = x / scale;
        double scaledY = y / scale;
        double scaledZ = z / scale;
        double norm = Math.sqrt(
                scaledX * scaledX + scaledY * scaledY + scaledZ * scaledZ);
        return new Unit(scaledX / norm, scaledY / norm, scaledZ / norm);
    }

    record Unit(double x, double y, double z) {
        List<BigDecimal> wire() {
            return List.of(
                    WireNumbers.direction(x),
                    WireNumbers.direction(y),
                    WireNumbers.direction(z));
        }

        float yaw() {
            float value = (float) Math.toDegrees(Math.atan2(-x, z));
            return value == 0.0f ? 0.0f : value;
        }

        float pitch() {
            float value = (float) Math.toDegrees(Math.atan2(-y, Math.hypot(x, z)));
            return value == 0.0f ? 0.0f : value;
        }
    }

    static final class ZeroDirectionException extends IllegalArgumentException {
        private ZeroDirectionException() {
            super("direction vector must be nonzero");
        }
    }
}
