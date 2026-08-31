package club.code2create.mcremote;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Wire numeric canonicalization owned by the plugin (DECISION 2026-08-19-01). */
final class WireNumbers {
    private WireNumbers() {
    }

    static BigDecimal position(double value) {
        return decimal(value, 3);
    }

    static BigDecimal direction(double value) {
        return decimal(value, 6);
    }

    static BigDecimal pitch(double value) {
        return decimal(value, 2);
    }

    static BigDecimal yaw(double value) {
        requireFinite(value);
        double normalized = normalizeYaw(value);
        BigDecimal rounded = decimal(normalized, 2);
        if (rounded.compareTo(BigDecimal.valueOf(180)) >= 0) {
            rounded = rounded.subtract(BigDecimal.valueOf(360));
        }
        return zeroWithoutScale(rounded);
    }

    static double normalizeYaw(double value) {
        requireFinite(value);
        double normalized = value % 360.0;
        if (normalized >= 180.0) {
            normalized -= 360.0;
        } else if (normalized < -180.0) {
            normalized += 360.0;
        }
        return normalized == 0.0 ? 0.0 : normalized;
    }

    static void requirePitch(double value) {
        requireFinite(value);
        if (value < -90.0 || value > 90.0) {
            throw new IllegalArgumentException("pitch must be within -90..90");
        }
    }

    private static BigDecimal decimal(double value, int scale) {
        requireFinite(value);
        return zeroWithoutScale(BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP));
    }

    private static BigDecimal zeroWithoutScale(BigDecimal value) {
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
    }
}
