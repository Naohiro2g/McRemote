package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireNumbersTest {
    @Test
    void positionUsesDecimalHalfUpForPositiveAndNegativeTies() {
        assertEquals(new BigDecimal("1.235"), WireNumbers.position(1.2345));
        assertEquals(new BigDecimal("-1.235"), WireNumbers.position(-1.2345));
    }

    @Test
    void anglesUseTwoPlacesAndRemoveNegativeZero() {
        assertEquals(new BigDecimal("12.35"), WireNumbers.pitch(12.345));
        assertEquals(BigDecimal.ZERO, WireNumbers.position(-0.0004));
        assertEquals(BigDecimal.ZERO, WireNumbers.yaw(-0.0));
    }

    @Test
    void yawNormalizesRoundsAndNormalizesBoundaryAgain() {
        assertEquals(new BigDecimal("-180"), WireNumbers.yaw(180));
        assertEquals(new BigDecimal("-179"), WireNumbers.yaw(181));
        assertEquals(new BigDecimal("179"), WireNumbers.yaw(-181));
        assertEquals(new BigDecimal("-180"), WireNumbers.yaw(540));
        assertEquals(new BigDecimal("-180"), WireNumbers.yaw(179.995));
    }

    @Test
    void pitchValidationAcceptsEndsAndRejectsOutside() {
        WireNumbers.requirePitch(-90);
        WireNumbers.requirePitch(90);
        assertThrows(IllegalArgumentException.class, () -> WireNumbers.requirePitch(-90.0001));
        assertThrows(IllegalArgumentException.class, () -> WireNumbers.requirePitch(90.0001));
    }

    @Test
    void nonFiniteValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WireNumbers.position(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> WireNumbers.yaw(Double.POSITIVE_INFINITY));
    }

    @Test
    void arbitraryFiniteYawCanBeReducedBeforePaperFloatConversion() {
        double normalized = WireNumbers.normalizeYaw(Double.MAX_VALUE);
        assertEquals(true, Double.isFinite(normalized));
        assertEquals(true, normalized >= -180.0 && normalized < 180.0);
    }
}
