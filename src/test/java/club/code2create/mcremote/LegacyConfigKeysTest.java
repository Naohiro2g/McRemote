package club.code2create.mcremote;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Startup migration of legacy b5:／b7: keys（DECISIONS 2026-09-03-02）。 */
class LegacyConfigKeysTest {
    @Test
    void changedLegacyValuesMoveToMeaningCategoriesInsteadOfDefaults() throws IOException {
        YamlConfiguration config = operatorConfig();
        config.set("b5.entity_handle_capacity", 8);
        config.set("b5.max_particle_count", 100);
        config.set("b7.lightning.global_per_tick", 5);

        LegacyConfigKeys.Migration migration = LegacyConfigKeys.migrate(config, packaged());

        assertEquals(8, config.getInt("entities.handle_capacity"));
        assertEquals(100, config.getInt("particles.max_count"));
        assertEquals(5, config.getInt("lightning.global_per_tick"));
        assertTrue(migration.moved().contains("b5.entity_handle_capacity -> entities.handle_capacity"));
        assertFalse(config.contains("b5", true), "emptied b5 section is removed");
        assertFalse(config.contains("b7", true), "emptied b7 section is removed");
        assertEquals(8, RuntimePolicy.from(config).entityHandleCapacity());
        assertEquals(5, LightningRuntimePolicy.from(config).globalPerTick());
    }

    @Test
    void newKeyWinsAndTheLegacyKeyIsRemovedAsIgnored() throws IOException {
        YamlConfiguration config = operatorConfig();
        config.set("entities.handle_capacity", 32);
        config.set("b5.entity_handle_capacity", 8);

        LegacyConfigKeys.Migration migration = LegacyConfigKeys.migrate(config, packaged());

        assertEquals(32, config.getInt("entities.handle_capacity"));
        assertFalse(config.contains("b5.entity_handle_capacity", true));
        assertEquals(List.of("b5.entity_handle_capacity (ignored; entities.handle_capacity is set)"),
                migration.removed());
    }

    @Test
    void freshConfigOnlyGainsDefaultsAndSecondStartupChangesNothing() throws IOException {
        YamlConfiguration config = operatorConfig();

        LegacyConfigKeys.Migration first = LegacyConfigKeys.migrate(config, packaged());
        assertTrue(first.added().contains("entities.handle_capacity"));
        assertTrue(first.moved().isEmpty());
        assertEquals(256, config.getInt("entities.handle_capacity"));

        LegacyConfigKeys.Migration second = LegacyConfigKeys.migrate(config, packaged());
        assertFalse(second.changed());
    }

    @Test
    void unknownKeysKeepTheirLegacySection() throws IOException {
        YamlConfiguration config = operatorConfig();
        config.set("b5.entity_handle_capacity", 8);
        config.set("b5.operator_note", "kept");

        LegacyConfigKeys.migrate(config, packaged());

        assertFalse(config.contains("b5.entity_handle_capacity", true));
        assertEquals("kept", config.getString("b5.operator_note"));
    }

    @Test
    void everyLegacyPathMapsToAPackagedKey() throws IOException {
        YamlConfiguration packaged = packaged();
        for (String path : LegacyConfigKeys.LEGACY_PATHS.keySet()) {
            assertTrue(packaged.contains(path), path + " must exist in the packaged config.yml");
        }
    }

    @Test
    void individualConnectionOverridesRetireOnceWithoutRemovingOtherAuthAndQueueSettings() throws IOException {
        YamlConfiguration config = operatorConfig();
        for (String path : PreAuthPolicy.RETIRED_CONFIG_KEYS) config.set(path, 7);
        config.set("auth.max_sessions_per_uuid", 3);
        config.set("auth.pair_code_ttl_seconds", 300);
        config.set("connection.command_queue_capacity", 42);
        LegacyConfigKeys.Migration migration = LegacyConfigKeys.migrate(config, packaged());
        for (String path : PreAuthPolicy.RETIRED_CONFIG_KEYS) {
            assertFalse(config.contains(path, true));
            assertFalse(packaged().contains(path));
            assertTrue(migration.removed().stream().anyMatch(item -> item.startsWith(path + " ")));
        }
        assertEquals(3, config.getInt("auth.max_sessions_per_uuid"));
        assertEquals(300, config.getInt("auth.pair_code_ttl_seconds"));
        assertEquals(42, config.getInt("connection.command_queue_capacity"));
        assertFalse(LegacyConfigKeys.migrate(config, packaged()).changed());
    }

    @Test
    void missingBuildBlocksGetsDistributionDefaultAndExplicitValuesSurviveMigration() throws IOException {
        YamlConfiguration missing = operatorConfig();
        missing.setDefaults(packaged());
        assertTrue(LegacyConfigKeys.migrate(missing, packaged()).added().contains("default_build_blocks"));
        assertTrue(missing.contains("default_build_blocks", true));
        assertEquals(32768, missing.getInt("default_build_blocks"));
        assertFalse(LegacyConfigKeys.migrate(missing, packaged()).changed());

        for (int explicit : new int[]{0, 256, 4096, 32769}) {
            YamlConfiguration config = operatorConfig();
            config.set("default_build_blocks", explicit);
            config.setDefaults(packaged());
            assertFalse(LegacyConfigKeys.migrate(config, packaged()).added().contains("default_build_blocks"));
            assertEquals(explicit, config.getInt("default_build_blocks"));
        }
    }

    /** An operator config written before the migration: only the top-level keys Stack renders. */
    private static YamlConfiguration operatorConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("api_port", 25575);
        config.set("auth.enforcement", true);
        return config;
    }

    private static YamlConfiguration packaged() throws IOException {
        try (var stream = LegacyConfigKeysTest.class.getResourceAsStream("/config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}
