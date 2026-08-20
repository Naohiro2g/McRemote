package club.code2create.mcremote;

import org.bukkit.configuration.file.FileConfiguration;

record B5RuntimePolicy(
        int eventRingCapacity,
        int eventRingBytes,
        int eventPollLimit,
        int entityHandleCapacity,
        int maxParticleCount,
        int maxWorkPerRequest,
        int sessionWorkPerTick,
        int playerWorkPerTick,
        int globalWorkPerTick,
        int connectionQueueCapacity
) {
    static B5RuntimePolicy from(FileConfiguration config) {
        return new B5RuntimePolicy(
                positive(config.getInt("b5.event_ring_capacity", 256)),
                positive(config.getInt("b5.event_ring_bytes", 262_144)),
                positive(config.getInt("b5.event_poll_limit", 64)),
                positive(config.getInt("b5.entity_handle_capacity", 256)),
                positive(config.getInt("b5.max_particle_count", 1_000)),
                positive(config.getInt("b5.max_work_per_request", 4_096)),
                positive(config.getInt("b5.session_work_per_tick", 4_096)),
                positive(config.getInt("b5.player_work_per_tick", 8_192)),
                positive(config.getInt("b5.global_work_per_tick", 32_768)),
                positive(config.getInt("b5.connection_queue_capacity", 1_024)));
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }
}
