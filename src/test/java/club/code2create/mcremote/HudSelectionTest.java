package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HudSelectionTest {
    @Test
    void firstConnectionIsDefaultAndAnExplicitSelectionSurvivesOrdinaryUpdates() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        HudSelection state = new HudSelection();
        assertEquals(first, state.reconcile(List.of(first, second)));
        assertTrue(state.select(second, List.of(first, second)));
        assertEquals(second, state.reconcile(List.of(first, second)));
    }

    @Test
    void selectedDisconnectFallsBackToEarliestSurvivingConnection() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        HudSelection state = new HudSelection();
        state.select(third, List.of(first, second, third));
        assertEquals(first, state.reconcile(List.of(first, second)));
        assertEquals(second, state.reconcile(List.of(second)));
        assertNull(state.reconcile(List.of()));
    }

    @Test
    void closedOrForeignCallersCannotStealSelectionAndReconnectJoinsAtEnd() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), reconnected = UUID.randomUUID();
        HudSelection state = new HudSelection();
        assertEquals(first, state.reconcile(List.of(first, second)));
        assertFalse(state.select(UUID.randomUUID(), List.of(first, second)));
        assertEquals(first, state.reconcile(List.of(first, second)));
        assertEquals(second, state.reconcile(List.of(second, reconnected)));
        assertFalse(state.select(first, List.of(second, reconnected)));
        assertEquals(second, state.reconcile(List.of(second, reconnected)));
    }

    @Test
    void playersHaveIndependentSelections() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        HudSelection alice = new HudSelection(), bob = new HudSelection();
        assertEquals(first, alice.reconcile(List.of(first)));
        assertEquals(second, bob.reconcile(List.of(second)));
        assertNull(alice.reconcile(List.of()));
        assertEquals(second, bob.reconcile(List.of(second)));
    }
}
