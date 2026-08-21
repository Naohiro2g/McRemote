package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

final class EventCommands {
    private final RemoteSession session;
    private final EventRing ring;
    private final int defaultPollLimit;
    private final int maxPollLimit;

    EventCommands(RemoteSession session, EventRing ring, int defaultPollLimit, int maxPollLimit) {
        if (defaultPollLimit < 1 || maxPollLimit < 1 || defaultPollLimit > maxPollLimit) {
            throw new IllegalArgumentException("invalid event poll runtime policy");
        }
        this.session = session;
        this.ring = ring;
        this.defaultPollLimit = defaultPollLimit;
        this.maxPollLimit = maxPollLimit;
    }

    void handlePoll(JsonElement params) {
        try {
            PollRequest request = parseRequest(params, defaultPollLimit, maxPollLimit);
            session.respondResult(ring.poll(request.afterSequence(), request.maxEvents()));
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        }
    }

    static PollRequest parseRequest(JsonElement params, int defaultPollLimit, int maxPollLimit) {
        if (defaultPollLimit < 1 || maxPollLimit < 1 || defaultPollLimit > maxPollLimit) {
            throw new IllegalArgumentException("invalid event poll runtime policy");
        }
        JsonArray args = WireParams.positional(params, 1, 2);
        long afterSequence = WireParams.longInteger(args, 0);
        int effectiveLimit = defaultPollLimit;
        if (args.size() == 2) {
            JsonElement rawOptions = args.get(1);
            if (rawOptions == null || !rawOptions.isJsonObject()) {
                throw new IllegalArgumentException("poll options must be an object");
            }
            JsonObject options = rawOptions.getAsJsonObject();
            if (options.size() != 1 || !options.has("max_events")) {
                throw new IllegalArgumentException("unknown or missing poll option");
            }
            effectiveLimit = positiveIntegerCapped(options.get("max_events"), maxPollLimit);
        }
        return new PollRequest(afterSequence, effectiveLimit);
    }

    private static int positiveIntegerCapped(JsonElement value, int cap) {
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("max_events must be a positive integer");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IllegalArgumentException("max_events must be a positive integer");
        }
        try {
            BigDecimal requested = new BigDecimal(primitive.getAsString());
            if (requested.signum() <= 0) {
                throw new IllegalArgumentException("max_events must be positive");
            }
            if (requested.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("max_events must be an integer");
            }
            if (requested.compareTo(BigDecimal.valueOf(cap)) >= 0) {
                return cap;
            }
            return requested.intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("max_events must be a positive integer", e);
        }
    }

    record PollRequest(long afterSequence, int maxEvents) {
    }
}
