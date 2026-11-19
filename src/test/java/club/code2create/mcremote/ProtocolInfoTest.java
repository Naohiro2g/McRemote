package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolInfoTest {
    @Test
    void advertisesProtocol2400AndAcceptsSameMajorEarlierOrEqualMinorAndAnyPatch() {
        assertEquals("24.0.0", ProtocolInfo.PROTOCOL);
        assertEquals("McRemote", ProtocolInfo.SERVER_NAME);
        assertEquals("2400.0.0rc1", ProtocolInfo.PLUGIN_VERSION);
        assertTrue(ProtocolInfo.isCompatible("24.0.0"));
        assertTrue(ProtocolInfo.isCompatible("24.0.9"));
    }

    @Test
    void rejectsOtherMajorUnsupportedMinorAndMalformedVersion() {
        assertFalse(ProtocolInfo.isCompatible("22.0.0"));
        assertFalse(ProtocolInfo.isCompatible("23.0.0"));
        assertFalse(ProtocolInfo.isCompatible("23.1.0"));
        assertFalse(ProtocolInfo.isCompatible("23.2.0"));
        assertFalse(ProtocolInfo.isCompatible("23.2.1"));
        assertFalse(ProtocolInfo.isCompatible("23.3.0"));
        assertFalse(ProtocolInfo.isCompatible("23.3.9"));
        assertFalse(ProtocolInfo.isCompatible("24.1.0"));
        assertFalse(ProtocolInfo.isCompatible("25.0.0"));
        assertFalse(ProtocolInfo.isCompatible(null));
        assertFalse(ProtocolInfo.isCompatible("24.0"));
        assertFalse(ProtocolInfo.isCompatible("invalid"));
    }
}
