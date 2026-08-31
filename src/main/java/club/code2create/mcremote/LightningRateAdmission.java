package club.code2create.mcremote;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Atomic connection/player/global rate gate, advanced once per server tick. */
final class LightningRateAdmission {
    enum Result { ACCEPTED, BACKPRESSURE }

    private final LightningRuntimePolicy policy;
    private final Map<UUID, Long> connectionLastAccepted = new HashMap<>();
    private final Map<UUID, Long> playerLastAccepted = new HashMap<>();
    private final ArrayDeque<Long> globalAcceptedTicks = new ArrayDeque<>();
    private long tick = -1;
    private int acceptedThisTick;

    LightningRateAdmission(LightningRuntimePolicy policy) {
        this.policy = policy;
    }

    synchronized void beginTick() {
        tick++;
        acceptedThisTick = 0;
        pruneCooldowns(connectionLastAccepted, policy.connectionCooldownTicks());
        pruneCooldowns(playerLastAccepted, policy.playerCooldownTicks());
        long firstIncludedTick = tick - policy.rollingWindowTicks() + 1L;
        while (!globalAcceptedTicks.isEmpty() && globalAcceptedTicks.peekFirst() < firstIncludedTick) {
            globalAcceptedTicks.removeFirst();
        }
    }

    synchronized Result admit(UUID connectionEpoch, UUID player) {
        if (tick < 0) {
            throw new IllegalStateException("beginTick must run before lightning admission");
        }
        if (connectionEpoch == null || player == null
                || connectionLastAccepted.containsKey(connectionEpoch)
                || playerLastAccepted.containsKey(player)
                || acceptedThisTick >= policy.globalPerTick()
                || globalAcceptedTicks.size() >= policy.globalPerWindow()) {
            return Result.BACKPRESSURE;
        }

        connectionLastAccepted.put(connectionEpoch, tick);
        playerLastAccepted.put(player, tick);
        globalAcceptedTicks.addLast(tick);
        acceptedThisTick++;
        return Result.ACCEPTED;
    }

    private void pruneCooldowns(Map<UUID, Long> accepted, int cooldownTicks) {
        Iterator<Map.Entry<UUID, Long>> iterator = accepted.entrySet().iterator();
        while (iterator.hasNext()) {
            if (tick - iterator.next().getValue() >= cooldownTicks) {
                iterator.remove();
            }
        }
    }
}
