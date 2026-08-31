package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightningRateAdmissionTest {
    private static final LightningRuntimePolicy POLICY = new LightningRuntimePolicy(20, 20, 2, 20, 8);

    @Test
    void connectionAndPlayerCooldownsUseExactTwentyTickBoundary() {
        LightningRateAdmission admission = started();
        UUID connection = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        assertEquals(LightningRateAdmission.Result.ACCEPTED, admission.admit(connection, player));
        advance(admission, 19);
        assertEquals(LightningRateAdmission.Result.BACKPRESSURE, admission.admit(connection, player));
        admission.beginTick();
        assertEquals(LightningRateAdmission.Result.ACCEPTED, admission.admit(connection, player));
    }

    @Test
    void playerCooldownAppliesAcrossConnections() {
        LightningRateAdmission admission = started();
        UUID player = UUID.randomUUID();

        assertEquals(LightningRateAdmission.Result.ACCEPTED,
                admission.admit(UUID.randomUUID(), player));
        assertEquals(LightningRateAdmission.Result.BACKPRESSURE,
                admission.admit(UUID.randomUUID(), player));
    }

    @Test
    void globalSameTickAllowsTwoAndRejectsThirdAtomically() {
        LightningRateAdmission admission = started();

        assertAcceptedFresh(admission);
        assertAcceptedFresh(admission);
        assertEquals(LightningRateAdmission.Result.BACKPRESSURE,
                admission.admit(UUID.randomUUID(), UUID.randomUUID()));

        admission.beginTick();
        assertAcceptedFresh(admission);
    }

    @Test
    void rollingWindowIsInclusiveAndExpiresAtTickTwenty() {
        LightningRateAdmission admission = started();
        for (int tick = 0; tick < 4; tick++) {
            if (tick > 0) {
                admission.beginTick();
            }
            assertAcceptedFresh(admission);
            assertAcceptedFresh(admission);
        }
        admission.beginTick();
        assertEquals(LightningRateAdmission.Result.BACKPRESSURE,
                admission.admit(UUID.randomUUID(), UUID.randomUUID()));

        advance(admission, 16);
        assertAcceptedFresh(admission);
    }

    @Test
    void rejectedAttemptDoesNotConsumeAnyOtherGate() {
        LightningRateAdmission admission = started();
        UUID acceptedConnection = UUID.randomUUID();
        UUID acceptedPlayer = UUID.randomUUID();
        assertEquals(LightningRateAdmission.Result.ACCEPTED,
                admission.admit(acceptedConnection, acceptedPlayer));

        UUID freshConnection = UUID.randomUUID();
        assertEquals(LightningRateAdmission.Result.BACKPRESSURE,
                admission.admit(freshConnection, acceptedPlayer));
        assertEquals(LightningRateAdmission.Result.ACCEPTED,
                admission.admit(freshConnection, UUID.randomUUID()));
    }

    private static LightningRateAdmission started() {
        LightningRateAdmission admission = new LightningRateAdmission(POLICY);
        admission.beginTick();
        return admission;
    }

    private static void advance(LightningRateAdmission admission, int ticks) {
        for (int i = 0; i < ticks; i++) {
            admission.beginTick();
        }
    }

    private static void assertAcceptedFresh(LightningRateAdmission admission) {
        assertEquals(LightningRateAdmission.Result.ACCEPTED,
                admission.admit(UUID.randomUUID(), UUID.randomUUID()));
    }
}
