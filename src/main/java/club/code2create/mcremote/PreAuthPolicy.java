package club.code2create.mcremote;

import org.bukkit.configuration.ConfigurationSection;

/** Server-local limits; these provisional defaults are not protocol constants. */
record PreAuthPolicy(int maxConnections, int maxPendingConnections, int acceptsPerSecond,
                     int maxFrameBytes, int commandQueueBytes, int idleSeconds,
                     int helloSeconds, int pairBeginsPerSecond, int pairPollsPerSecond,
                     int maxPendingPairs) {
    static PreAuthPolicy from(ConfigurationSection config) {
        return new PreAuthPolicy(
                read(config, "connection.max_connections", 128),
                read(config, "connection.max_pre_hello_connections", 32),
                read(config, "connection.accepts_per_second", 32),
                read(config, "connection.max_frame_bytes", 65_536),
                read(config, "connection.command_queue_bytes", 1_048_576),
                read(config, "connection.pre_hello_idle_seconds", 30),
                read(config, "connection.hello_timeout_seconds", 180),
                read(config, "auth.pair_begins_per_second", 8),
                read(config, "auth.pair_polls_per_second", 128),
                read(config, "auth.max_pending_pairs", 128));
    }

    private static int read(ConfigurationSection config, String key, int fallback) {
        int value = config.getInt(key, fallback);
        if (value < 1) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }
}
