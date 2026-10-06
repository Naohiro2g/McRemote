package club.code2create.mcremote;

import java.util.List;

/** Server-local limits; these provisional defaults are not protocol constants. */
record PreAuthPolicy(int maxConnections, int maxPendingConnections, int acceptsPerSecond,
                     int maxFrameBytes, int commandQueueBytes, int idleSeconds,
                     int helloSeconds, int pairBeginsPerSecond, int pairPollsPerSecond,
                     int maxPendingPairs) {
    static final List<String> RETIRED_CONFIG_KEYS = List.of(
            "connection.max_connections", "connection.max_pre_hello_connections",
            "connection.accepts_per_second", "connection.max_frame_bytes",
            "connection.command_queue_bytes", "connection.pre_hello_idle_seconds",
            "connection.hello_timeout_seconds", "auth.pair_begins_per_second",
            "auth.pair_polls_per_second", "auth.max_pending_pairs");

    static int profilePlayers(int maxPlayers) {
        return maxPlayers <= 16 ? 16 : maxPlayers <= 24 ? 24 : 32;
    }

    static PreAuthPolicy forMaxPlayers(int maxPlayers, long pairCodeTtlSeconds) {
        if (pairCodeTtlSeconds < 1 || pairCodeTtlSeconds > Integer.MAX_VALUE - 60L) {
            throw new IllegalArgumentException("auth.pair_code_ttl_seconds is outside the supported range");
        }
        int players = profilePlayers(maxPlayers);
        return new PreAuthPolicy(
                players * 4, players * 2, (players + 4) * 4,
                65_536, 1_048_576, 30,
                (int) Math.max(180, pairCodeTtlSeconds + 60),
                players + 4, players * 4, players * 2);
    }
}
