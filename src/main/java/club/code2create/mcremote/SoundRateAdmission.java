package club.code2create.mcremote;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tick cap on sounds for world.playSound／world.playBlockSound: per connection and server-wide.
 * Several sounds may share a tick (chords), but a flood is rejected with backpressure.
 */
final class SoundRateAdmission {
    enum Result { ACCEPTED, BACKPRESSURE }

    /** Server-local limits; not protocol constants. */
    record Policy(int perConnectionPerTick, int globalPerTick) {
        static final int DEFAULT_PER_CONNECTION_PER_TICK = 16;
        static final int DEFAULT_GLOBAL_PER_TICK = 64;

        static Policy from(ConfigurationSection config) {
            return new Policy(
                    Math.max(1, config.getInt("sound.per_connection_per_tick", DEFAULT_PER_CONNECTION_PER_TICK)),
                    Math.max(1, config.getInt("sound.global_per_tick", DEFAULT_GLOBAL_PER_TICK)));
        }
    }

    private final Policy policy;
    private final Map<UUID, Integer> perConnection = new HashMap<>();
    private int global;

    SoundRateAdmission(Policy policy) {
        this.policy = policy;
    }

    synchronized void beginTick() {
        perConnection.clear();
        global = 0;
    }

    synchronized Result admit(UUID connectionEpoch) {
        int connectionCount = perConnection.getOrDefault(connectionEpoch, 0);
        if (connectionEpoch == null || connectionCount >= policy.perConnectionPerTick()
                || global >= policy.globalPerTick()) {
            return Result.BACKPRESSURE;
        }
        perConnection.put(connectionEpoch, connectionCount + 1);
        global++;
        return Result.ACCEPTED;
    }
}
