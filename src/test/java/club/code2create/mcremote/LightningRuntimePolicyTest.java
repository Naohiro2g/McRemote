package club.code2create.mcremote;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightningRuntimePolicyTest {
    @Test
    void emptyConfigUsesLockedDistributionDefaults() {
        LightningRuntimePolicy policy = LightningRuntimePolicy.from(new YamlConfiguration());

        assertEquals(20, policy.connectionCooldownTicks());
        assertEquals(20, policy.playerCooldownTicks());
        assertEquals(2, policy.globalPerTick());
        assertEquals(20, policy.rollingWindowTicks());
        assertEquals(8, policy.globalPerWindow());
        assertEquals(256, LightningCommands.WORK_UNITS);
    }

    @Test
    void valuesRemainOperatorPolicyAndInvalidNonPositiveValuesClampToOne() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("b7.lightning.connection_cooldown_ticks", 7);
        config.set("b7.lightning.player_cooldown_ticks", 0);
        config.set("b7.lightning.global_per_tick", 3);
        config.set("b7.lightning.rolling_window_ticks", 12);
        config.set("b7.lightning.global_per_window", 5);
        config.set("b7.lightning.work_units", 1);

        LightningRuntimePolicy policy = LightningRuntimePolicy.from(config);

        assertEquals(new LightningRuntimePolicy(7, 1, 3, 12, 5), policy);
        assertEquals(256, LightningCommands.WORK_UNITS,
                "work cost is fixed and not configurable runtime policy");
    }
}
