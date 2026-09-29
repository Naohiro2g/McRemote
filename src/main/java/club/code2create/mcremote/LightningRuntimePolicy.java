package club.code2create.mcremote;

import org.bukkit.configuration.ConfigurationSection;

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

    static LightningRuntimePolicy from(ConfigurationSection config) {
        return new LightningRuntimePolicy(
                positive(config, "connection_cooldown_ticks", DEFAULT_CONNECTION_COOLDOWN_TICKS),
                positive(config, "player_cooldown_ticks", DEFAULT_PLAYER_COOLDOWN_TICKS),
                positive(config, "global_per_tick", DEFAULT_GLOBAL_PER_TICK),
                positive(config, "rolling_window_ticks", DEFAULT_ROLLING_WINDOW_TICKS),
                positive(config, "global_per_window", DEFAULT_GLOBAL_PER_WINDOW));
    }

    private static int positive(ConfigurationSection config, String key, int fallback) {
        return Math.max(1, LegacyConfigKeys.getInt(
                config, "lightning." + key, "b7.lightning." + key, fallback));
    }
}
