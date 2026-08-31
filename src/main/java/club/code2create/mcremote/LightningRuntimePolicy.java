package club.code2create.mcremote;

import org.bukkit.configuration.file.FileConfiguration;

/** Server-local limits for the protocol 23.1 damage-capable lightning command. */
record LightningRuntimePolicy(
        int connectionCooldownTicks,
        int playerCooldownTicks,
        int globalPerTick,
        int rollingWindowTicks,
        int globalPerWindow
) {
    static final int DEFAULT_CONNECTION_COOLDOWN_TICKS = 20;
    static final int DEFAULT_PLAYER_COOLDOWN_TICKS = 20;
    static final int DEFAULT_GLOBAL_PER_TICK = 2;
    static final int DEFAULT_ROLLING_WINDOW_TICKS = 20;
    static final int DEFAULT_GLOBAL_PER_WINDOW = 8;
    LightningRuntimePolicy {
        if (connectionCooldownTicks < 1 || playerCooldownTicks < 1
                || globalPerTick < 1 || rollingWindowTicks < 1
                || globalPerWindow < 1) {
            throw new IllegalArgumentException("lightning policy values must be positive");
        }
    }

    static LightningRuntimePolicy from(FileConfiguration config) {
        return new LightningRuntimePolicy(
                positive(config, "b7.lightning.connection_cooldown_ticks",
                        DEFAULT_CONNECTION_COOLDOWN_TICKS),
                positive(config, "b7.lightning.player_cooldown_ticks",
                        DEFAULT_PLAYER_COOLDOWN_TICKS),
                positive(config, "b7.lightning.global_per_tick", DEFAULT_GLOBAL_PER_TICK),
                positive(config, "b7.lightning.rolling_window_ticks", DEFAULT_ROLLING_WINDOW_TICKS),
                positive(config, "b7.lightning.global_per_window", DEFAULT_GLOBAL_PER_WINDOW));
    }

    private static int positive(FileConfiguration config, String path, int fallback) {
        return Math.max(1, config.getInt(path, fallback));
    }
}
