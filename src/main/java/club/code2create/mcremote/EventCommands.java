package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

final class EventCommands {
    private final RemoteSession session;
    private final EventRing ring;
    private final int maxPollLimit;

    EventCommands(RemoteSession session, EventRing ring, int maxPollLimit) {
        this.session = session;
        this.ring = ring;
        this.maxPollLimit = maxPollLimit;
    }

    void handlePoll(JsonElement params) {
        try {
            JsonArray args = WireParams.positional(params, 2);
            long afterSequence = WireParams.longInteger(args, 0);
            int limit = WireParams.integer(args, 1);
            if (limit < 1 || limit > maxPollLimit) {
                throw new IllegalArgumentException("poll limit is outside runtime policy");
            }
            session.respondResult(ring.poll(afterSequence, limit));
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        }
    }
}
