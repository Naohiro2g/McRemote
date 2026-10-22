package club.code2create.mcremote;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** Stable rejects obsolete configuration before opening sockets or changing persistent state. */
class LegacyConfigKeysTest {
    @Test
    void everyLegacyKeyNamesItsReplacementWithoutMovingOrRemovingValues() throws IOException {
        for (var entry : LegacyConfigKeys.LEGACY_PATHS.entrySet()) {
            YamlConfiguration config = operatorConfig();
            config.setDefaults(packaged());
            config.set(entry.getValue(), 7);
            String before = config.saveToString();
            List<String> errors = LegacyConfigKeys.errors(config);
            assertEquals(1, errors.size(), entry.getValue());
            assertTrue(errors.getFirst().contains("'" + entry.getValue() + "'"));
            assertTrue(errors.getFirst().contains("'" + entry.getKey() + "'"));
            assertEquals(before, config.saveToString(), "rejection must not migrate config");
            assertFalse(config.contains(entry.getKey(), true));
        }
    }

    @Test
    void legacyKeysStillRejectWhenTheirReplacementIsAlreadySet() {
        YamlConfiguration config = operatorConfig();
        config.set("entities.handle_capacity", 32);
        config.set("b5.entity_handle_capacity", 8);
        assertEquals(1, LegacyConfigKeys.errors(config).size());
        assertEquals(32, config.getInt("entities.handle_capacity"));
        assertEquals(8, config.getInt("b5.entity_handle_capacity"));
    }

    @Test
    void freshConfigOnlyGainsDefaultsAndSecondStartupChangesNothing() throws IOException {
        YamlConfiguration config = operatorConfig();

        assertTrue(LegacyConfigKeys.errors(config).isEmpty());
        LegacyConfigKeys.Update first = LegacyConfigKeys.addMissingDefaults(config, packaged());
        assertTrue(first.added().contains("entities.handle_capacity"));
        assertEquals(256, config.getInt("entities.handle_capacity"));

        LegacyConfigKeys.Update second = LegacyConfigKeys.addMissingDefaults(config, packaged());
        assertFalse(second.changed());
    }

    @Test
    void emptyMalformedAndUnknownLegacySectionsAlsoRejectWithoutLoggingTheirValues() {
        for (String yaml : List.of("b5: {}", "b7: {}", "b7:\n  lightning: {}",
                "b5: obsolete-value", "b7:\n  unknown: private-value")) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml));
            String before = config.saveToString();
            List<String> errors = LegacyConfigKeys.errors(config);
            assertEquals(1, errors.size(), yaml);
            assertTrue(errors.getFirst().contains("Remove"));
            assertFalse(errors.getFirst().contains("obsolete-value"));
            assertFalse(errors.getFirst().contains("private-value"));
            assertEquals(before, config.saveToString());
        }
    }

    @Test
    void rc1MigratedConfigRetainsAllExplicitSettings() throws IOException {
        YamlConfiguration config = packaged();
        for (String path : LegacyConfigKeys.LEGACY_PATHS.keySet()) config.set(path, 7);
        String before = config.saveToString();
        assertTrue(LegacyConfigKeys.errors(config).isEmpty());
        assertFalse(LegacyConfigKeys.addMissingDefaults(config, packaged()).changed());
        assertEquals(before, config.saveToString());
        assertEquals(7, RuntimePolicy.from(config).entityHandleCapacity());
        assertEquals(7, LightningRuntimePolicy.from(config).globalPerTick());
    }

    @Test
    void startupLogsCorrectionAndStopsPaperBeforeSavingOrInitializing() throws IOException {
        YamlConfiguration config = operatorConfig();
        config.setDefaults(packaged());
        config.set("b5.max_work_per_request", 1234);
        config.set("b7.lightning.global_per_tick", 7);
        String before = config.saveToString();
        McRemote plugin = mock(McRemote.class);
        Server server = mock(Server.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        doCallRealMethod().when(plugin).onEnable();
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            public void publish(LogRecord record) { messages.add(record.getMessage()); }
            public void flush() {}
            public void close() {}
        };
        Logger logger = Logger.getLogger("McRemote");
        McRemote previous = McRemote.instance;
        logger.addHandler(handler);
        try {
            plugin.onEnable();
        } finally {
            logger.removeHandler(handler);
            McRemote.instance = previous;
        }
        verify(server).shutdown();
        verifyNoMoreInteractions(server);
        verify(plugin, never()).saveConfig();
        assertEquals(before, config.saveToString());
        assertTrue(messages.stream().anyMatch(message -> message.contains("entire Paper server")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("b5.max_work_per_request")
                && message.contains("work.per_request")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("b7.lightning.global_per_tick")
                && message.contains("'lightning.global_per_tick'")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("remove the b5: and b7:")
                && message.contains("restart Paper")));
        assertFalse(messages.stream().anyMatch(message -> message.contains("Auth enforcement:")));
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
        LegacyConfigKeys.Update migration = LegacyConfigKeys.addMissingDefaults(config, packaged());
        for (String path : PreAuthPolicy.RETIRED_CONFIG_KEYS) {
            assertFalse(config.contains(path, true));
            assertFalse(packaged().contains(path));
            assertTrue(migration.removed().stream().anyMatch(item -> item.startsWith(path + " ")));
        }
        assertEquals(3, config.getInt("auth.max_sessions_per_uuid"));
        assertEquals(300, config.getInt("auth.pair_code_ttl_seconds"));
        assertEquals(42, config.getInt("connection.command_queue_capacity"));
        assertFalse(LegacyConfigKeys.addMissingDefaults(config, packaged()).changed());
    }

    @Test
    void missingBuildBlocksGetsDistributionDefaultAndExplicitValuesSurviveMigration() throws IOException {
        YamlConfiguration missing = operatorConfig();
        missing.setDefaults(packaged());
        assertTrue(LegacyConfigKeys.addMissingDefaults(missing, packaged()).added().contains("default_build_blocks"));
        assertTrue(missing.contains("default_build_blocks", true));
        assertEquals(32768, missing.getInt("default_build_blocks"));
        assertFalse(LegacyConfigKeys.addMissingDefaults(missing, packaged()).changed());

        for (int explicit : new int[]{0, 256, 4096, 32769}) {
            YamlConfiguration config = operatorConfig();
            config.set("default_build_blocks", explicit);
            config.setDefaults(packaged());
            assertFalse(LegacyConfigKeys.addMissingDefaults(config, packaged()).added().contains("default_build_blocks"));
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
