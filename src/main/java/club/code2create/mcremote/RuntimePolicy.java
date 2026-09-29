package club.code2create.mcremote;

import org.bukkit.configuration.ConfigurationSection;

/** events／entities／particles／work／connection の server-local な実装上限。protocol 定数ではない。 */
record RuntimePolicy(
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
        int connectionResponseQueueCapacity,
        int nearbyMaxRadius,
        int nearbyMaxEntities
) {
    /** protocol 23.2.0 の上限。runtime policy はこれより下げられるが上げられない（DECISIONS 2026-09-30-01）。 */
    static final int PROTOCOL_MAX_NEARBY_RADIUS = 64;
    static final int PROTOCOL_MAX_NEARBY_ENTITIES = 64;

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

    static RuntimePolicy from(ConfigurationSection config) {
        int eventPollLimit = read(config, "events.poll_limit", DEFAULT_EVENT_POLL_LIMIT);
        int eventPollDefault = read(config, "events.poll_default", DEFAULT_EVENT_POLL_DEFAULT);
        return new RuntimePolicy(
                read(config, "events.ring_capacity", DEFAULT_EVENT_RING_CAPACITY),
                read(config, "events.ring_bytes", DEFAULT_EVENT_RING_BYTES),
                Math.min(eventPollDefault, eventPollLimit),
                eventPollLimit,
                read(config, "entities.handle_capacity", DEFAULT_ENTITY_HANDLE_CAPACITY),
                read(config, "particles.max_count", DEFAULT_MAX_PARTICLE_COUNT),
                read(config, "work.per_request", DEFAULT_MAX_WORK_PER_REQUEST),
                read(config, "work.per_session_tick", DEFAULT_SESSION_WORK_PER_TICK),
                read(config, "work.per_player_tick", DEFAULT_PLAYER_WORK_PER_TICK),
                read(config, "work.global_per_tick", DEFAULT_GLOBAL_WORK_PER_TICK),
                read(config, "connection.command_queue_capacity", DEFAULT_CONNECTION_QUEUE_CAPACITY),
                read(config, "connection.response_queue_capacity", DEFAULT_CONNECTION_RESPONSE_QUEUE_CAPACITY),
                Math.min(PROTOCOL_MAX_NEARBY_RADIUS, Math.max(1, config.getInt(
                        "entities.nearby_max_radius", PROTOCOL_MAX_NEARBY_RADIUS))),
                Math.min(PROTOCOL_MAX_NEARBY_ENTITIES, Math.max(1, config.getInt(
                        "entities.nearby_max_entities", PROTOCOL_MAX_NEARBY_ENTITIES))));
    }

    private static int read(ConfigurationSection config, String path, int fallback) {
        return Math.max(1, LegacyConfigKeys.getInt(config, path, fallback));
    }
}
