package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolInfoTest {
    @Test
    void rc1AdvertisesProtocol2321AndAcceptsEarlierMinorAndPatch() {
        assertEquals("23.2.1", ProtocolInfo.PROTOCOL);
        assertTrue(ProtocolInfo.isCompatible("23.0.0"));
        assertTrue(ProtocolInfo.isCompatible("23.1.0"));
        assertTrue(ProtocolInfo.isCompatible("23.2.0"));
        assertTrue(ProtocolInfo.isCompatible("23.2.1"));
        assertTrue(ProtocolInfo.isCompatible("23.2.9"));
    }

    @Test
    void rejectsOtherMajorUnsupportedMinorAndMalformedVersion() {
        assertFalse(ProtocolInfo.isCompatible("22.0.0"));
        assertFalse(ProtocolInfo.isCompatible("24.0.0"));
        assertFalse(ProtocolInfo.isCompatible("23.3.0"));
        assertFalse(ProtocolInfo.isCompatible(null));
        assertFalse(ProtocolInfo.isCompatible("23.2"));
        assertFalse(ProtocolInfo.isCompatible("invalid"));
    }
}
