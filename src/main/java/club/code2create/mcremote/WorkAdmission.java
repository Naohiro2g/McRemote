package club.code2create.mcremote;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Fixed-policy per-session/player/global work admission, reset once per server tick. */
final class WorkAdmission {
    enum Result { ACCEPTED, BACKPRESSURE, WORK_LIMIT_EXCEEDED }

    private final B5RuntimePolicy policy;
    private final Map<UUID, Integer> sessionWork = new HashMap<>();
    private final Map<UUID, Integer> playerWork = new HashMap<>();
    private int globalWork;

    WorkAdmission(B5RuntimePolicy policy) {
        this.policy = policy;
    }

    synchronized void beginTick() {
        sessionWork.clear();
        playerWork.clear();
        globalWork = 0;
    }

    synchronized Result admit(UUID sessionEpoch, UUID player, int units) {
        if (units < 0 || units > policy.maxWorkPerRequest()) {
            return Result.WORK_LIMIT_EXCEEDED;
        }
        int nextSession = safeAdd(sessionWork.getOrDefault(sessionEpoch, 0), units);
        int nextPlayer = player == null ? 0 : safeAdd(playerWork.getOrDefault(player, 0), units);
        int nextGlobal = safeAdd(globalWork, units);
        if (nextSession > policy.sessionWorkPerTick()
                || player != null && nextPlayer > policy.playerWorkPerTick()
                || nextGlobal > policy.globalWorkPerTick()) {
            return Result.BACKPRESSURE;
        }
        sessionWork.put(sessionEpoch, nextSession);
        if (player != null) {
            playerWork.put(player, nextPlayer);
        }
        globalWork = nextGlobal;
        return Result.ACCEPTED;
    }

    private static int safeAdd(int left, int right) {
        long sum = (long) left + right;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
