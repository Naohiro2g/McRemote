package club.code2create.mcremote;

import org.bukkit.configuration.file.FileConfiguration;

record B5RuntimePolicy(
        int eventRingCapacity,
        int eventRingBytes,
        int eventPollDefault,
        int eventPollLimit,
        int entityHandleCapacity,
        int maxParticleCount,
        int maxWorkPerRequest,
        int sessionWorkPerTick,
        int playerWorkPerTick,
        int globalWorkPerTick,
        int connectionQueueCapacity,
        int connectionResponseQueueCapacity
) {
    static final int DEFAULT_EVENT_RING_CAPACITY = 256;
    static final int DEFAULT_EVENT_RING_BYTES = 262_144;
    static final int DEFAULT_EVENT_POLL_DEFAULT = 64;
    static final int DEFAULT_EVENT_POLL_LIMIT = 64;
    static final int DEFAULT_ENTITY_HANDLE_CAPACITY = 256;
    static final int DEFAULT_MAX_PARTICLE_COUNT = 1_000;
    static final int DEFAULT_MAX_WORK_PER_REQUEST = 4_096;
    static final int DEFAULT_SESSION_WORK_PER_TICK = 4_096;
    static final int DEFAULT_PLAYER_WORK_PER_TICK = 8_192;
    static final int DEFAULT_GLOBAL_WORK_PER_TICK = 32_768;
    static final int DEFAULT_CONNECTION_QUEUE_CAPACITY = 1_024;
    static final int DEFAULT_CONNECTION_RESPONSE_QUEUE_CAPACITY = 64;

    static B5RuntimePolicy from(FileConfiguration config) {
        int eventPollLimit = positive(config.getInt(
                "b5.event_poll_limit", DEFAULT_EVENT_POLL_LIMIT));
        int eventPollDefault = positive(config.getInt(
                "b5.event_poll_default", DEFAULT_EVENT_POLL_DEFAULT));
        return new B5RuntimePolicy(
                positive(config.getInt("b5.event_ring_capacity", DEFAULT_EVENT_RING_CAPACITY)),
                positive(config.getInt("b5.event_ring_bytes", DEFAULT_EVENT_RING_BYTES)),
                Math.min(eventPollDefault, eventPollLimit),
                eventPollLimit,
                positive(config.getInt("b5.entity_handle_capacity", DEFAULT_ENTITY_HANDLE_CAPACITY)),
                positive(config.getInt("b5.max_particle_count", DEFAULT_MAX_PARTICLE_COUNT)),
                positive(config.getInt("b5.max_work_per_request", DEFAULT_MAX_WORK_PER_REQUEST)),
                positive(config.getInt("b5.session_work_per_tick", DEFAULT_SESSION_WORK_PER_TICK)),
                positive(config.getInt("b5.player_work_per_tick", DEFAULT_PLAYER_WORK_PER_TICK)),
                positive(config.getInt("b5.global_work_per_tick", DEFAULT_GLOBAL_WORK_PER_TICK)),
                positive(config.getInt("b5.connection_queue_capacity", DEFAULT_CONNECTION_QUEUE_CAPACITY)),
                positive(config.getInt(
                        "b5.connection_response_queue_capacity",
                        DEFAULT_CONNECTION_RESPONSE_QUEUE_CAPACITY)));
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }
}
