package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkAdmissionTest {
    private static final RuntimePolicy POLICY = new RuntimePolicy(
            8, 8_000, 8, 8, 8, 10, 10, 6, 8, 12, 4, 4, 64, 64);

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

    @Test
    void defaultRequestCeilingIs32768WhileTickBudgetsRemainIndependent() {
        RuntimePolicy policy = RuntimePolicy.from(new YamlConfiguration());
        assertEquals(32768, policy.maxWorkPerRequest());
        assertEquals(4096, policy.sessionWorkPerTick());
        assertEquals(8192, policy.playerWorkPerTick());
        assertEquals(32768, policy.globalWorkPerTick());
        WorkAdmission admission = new WorkAdmission(policy);
        UUID session = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        assertEquals(WorkAdmission.Result.WORK_LIMIT_EXCEEDED, admission.admit(session, player, 32769));
        assertEquals(WorkAdmission.Result.BACKPRESSURE, admission.admit(session, player, 32768));
        assertEquals(WorkAdmission.Result.ACCEPTED, admission.admit(session, player, 4096));
    }

    @Test
    void requestBoundaryExecutesWhenAllTickBudgetsPermitIt() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("work.per_session_tick", 32768);
        config.set("work.per_player_tick", 32768);
        WorkAdmission admission = new WorkAdmission(RuntimePolicy.from(config));
        UUID session = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        assertEquals(WorkAdmission.Result.WORK_LIMIT_EXCEEDED, admission.admit(session, player, 32769));
        assertEquals(WorkAdmission.Result.ACCEPTED, admission.admit(session, player, 32768));
        assertEquals(WorkAdmission.Result.BACKPRESSURE, admission.admit(UUID.randomUUID(), null, 1));
        admission.beginTick();
        assertEquals(WorkAdmission.Result.ACCEPTED, admission.admit(session, player, 32768));
    }
}
