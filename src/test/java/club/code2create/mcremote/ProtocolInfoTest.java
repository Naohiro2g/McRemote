package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolInfoTest {
    @Test
    void b8AdvertisesProtocol232AndAcceptsEarlierMinor() {
        assertEquals("23.2.0", ProtocolInfo.PROTOCOL);
        assertTrue(ProtocolInfo.isCompatible("23.0.0"));
        assertTrue(ProtocolInfo.isCompatible("23.1.0"));
        assertTrue(ProtocolInfo.isCompatible("23.2.0"));
        assertTrue(ProtocolInfo.isCompatible("23.2.9"));
    }

    @Test
    void rejectsProtocol22AndUnsupported23Minor() {
        assertFalse(ProtocolInfo.isCompatible("22.0.0"));
        assertFalse(ProtocolInfo.isCompatible("24.0.0"));
        assertFalse(ProtocolInfo.isCompatible("23.3.0"));
    }
}
