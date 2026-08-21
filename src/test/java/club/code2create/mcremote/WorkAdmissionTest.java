package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkAdmissionTest {
    private static final B5RuntimePolicy POLICY = new B5RuntimePolicy(
            8, 8_000, 8, 8, 8, 10, 10, 6, 8, 12, 4, 4);

    @Test
    void distinguishesOversizedRequestFromTemporaryBudgetPressure() {
        WorkAdmission admission = new WorkAdmission(POLICY);
        UUID session = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        assertEquals(WorkAdmission.Result.WORK_LIMIT_EXCEEDED,
                admission.admit(session, player, 11));
        assertEquals(WorkAdmission.Result.ACCEPTED, admission.admit(session, player, 6));
        assertEquals(WorkAdmission.Result.BACKPRESSURE, admission.admit(session, player, 1));
    }

    @Test
    void budgetsResetAtTickBoundary() {
        WorkAdmission admission = new WorkAdmission(POLICY);
        UUID session = UUID.randomUUID();
        assertEquals(WorkAdmission.Result.ACCEPTED, admission.admit(session, null, 6));
        assertEquals(WorkAdmission.Result.BACKPRESSURE, admission.admit(session, null, 1));
        admission.beginTick();
        assertEquals(WorkAdmission.Result.ACCEPTED, admission.admit(session, null, 6));
    }
}
