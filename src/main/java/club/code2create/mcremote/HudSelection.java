package club.code2create.mcremote;

import java.util.List;
import java.util.UUID;

/** Main-thread selection for one player. IDs are internal connection epochs, never WireScope aliases. */
final class HudSelection {
    private UUID selected;

    UUID reconcile(List<UUID> activeInAcceptanceOrder) {
        if (selected == null || !activeInAcceptanceOrder.contains(selected)) {
            selected = activeInAcceptanceOrder.isEmpty() ? null : activeInAcceptanceOrder.getFirst();
        }
        return selected;
    }

    boolean select(UUID caller, List<UUID> activeInAcceptanceOrder) {
        if (caller == null || !activeInAcceptanceOrder.contains(caller)) {
            return false;
        }
        selected = caller;
        return true;
    }
}
