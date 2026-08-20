package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolInfoTest {
    @Test
    void b5AdvertisesProtocol22() {
        assertEquals("22.0.0", ProtocolInfo.PROTOCOL);
        assertTrue(ProtocolInfo.isCompatible("22.0.0"));
        assertTrue(ProtocolInfo.isCompatible("22.0.9"));
    }

    @Test
    void rejectsProtocol21AndUnsupported22Minor() {
        assertFalse(ProtocolInfo.isCompatible("21.0.0"));
        assertFalse(ProtocolInfo.isCompatible("23.0.0"));
        assertFalse(ProtocolInfo.isCompatible("22.1.0"));
    }
}
