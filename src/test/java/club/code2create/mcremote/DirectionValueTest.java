package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectionValueTest {
    @Test
    void preservesAllSixPaperAxes() {
        assertEquals(List.of(decimal("1"), decimal("0"), decimal("0")),
                DirectionValue.normalize(1, 0, 0).wire());
        assertEquals(List.of(decimal("-1"), decimal("0"), decimal("0")),
                DirectionValue.normalize(-1, 0, 0).wire());
        assertEquals(List.of(decimal("0"), decimal("1"), decimal("0")),
                DirectionValue.normalize(0, 1, 0).wire());
        assertEquals(List.of(decimal("0"), decimal("-1"), decimal("0")),
                DirectionValue.normalize(0, -1, 0).wire());
        assertEquals(List.of(decimal("0"), decimal("0"), decimal("1")),
                DirectionValue.normalize(0, 0, 1).wire());
        assertEquals(List.of(decimal("0"), decimal("0"), decimal("-1")),
                DirectionValue.normalize(0, 0, -1).wire());
    }

    @Test
    void normalizesAxesDiagonalAndExtremeMagnitudesWithoutOverflowOrUnderflow() {
        assertEquals(List.of(decimal("1"), decimal("0"), decimal("0")),
                DirectionValue.normalize(Double.MAX_VALUE, 0, 0).wire());
        assertEquals(List.of(decimal("0"), decimal("-1"), decimal("0")),
                DirectionValue.normalize(0, -Double.MIN_VALUE, 0).wire());
        assertEquals(List.of(decimal("0.57735"), decimal("0.57735"), decimal("0.57735")),
                DirectionValue.normalize(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE).wire());
    }

    @Test
    void wireComponentsUseAtMostSixFractionalDigitsHalfUpWithoutNegativeZero() {
        assertEquals(decimal("0.123457"), WireNumbers.direction(0.1234565));
        assertEquals(BigDecimal.ZERO, WireNumbers.direction(-0.0000001));
        for (BigDecimal component : DirectionValue.normalize(1, 1, 1).wire()) {
            assertTrue(component.scale() <= 6);
        }
    }

    @Test
    void roundedUnitVectorRemainsWithinLockedNormTolerance() {
        List<BigDecimal> wire = DirectionValue.normalize(1, 2, 3).wire();
        double norm = Math.sqrt(wire.stream()
                .mapToDouble(value -> value.doubleValue() * value.doubleValue())
                .sum());

        assertTrue(Math.abs(norm - 1.0) <= 1.5e-6, "rounded norm=" + norm);
    }

    @Test
    void scalarMultiplesHaveIdenticalWireDirection() {
        assertEquals(DirectionValue.normalize(1, 2, 3).wire(),
                DirectionValue.normalize(10, 20, 30).wire());
    }

    @Test
    void rejectsSignedZeroAndNonFiniteComponents() {
        assertThrows(DirectionValue.ZeroDirectionException.class,
                () -> DirectionValue.normalize(-0.0, 0.0, -0.0));
        assertThrows(IllegalArgumentException.class,
                () -> DirectionValue.normalize(Double.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> DirectionValue.normalize(0, Double.POSITIVE_INFINITY, 1));
    }

    @Test
    void mapsUnitDirectionToPaperYawAndPitch() {
        assertEquals(-90.0f, DirectionValue.normalize(1, 0, 0).yaw());
        assertEquals(0.0f, DirectionValue.normalize(1, 0, 0).pitch());
        assertEquals(-90.0f, DirectionValue.normalize(0, 1, 0).pitch());
        assertEquals(90.0f, DirectionValue.normalize(0, -1, 0).pitch());
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
