package club.code2create.mcremote;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimePolicyTest {
    private static final RuntimePolicy DEFAULTS = RuntimePolicy.from(new YamlConfiguration());

    @Test
    void packagedConfigUsesMeaningCategoriesWithTheDefaultValues() throws IOException {
        try (var stream = RuntimePolicyTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "packaged config.yml");
            YamlConfiguration packaged = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));

            assertFalse(packaged.contains("b5"), "release-numbered category b5 must not be packaged");
            assertFalse(packaged.contains("b7"), "release-numbered category b7 must not be packaged");
            assertEquals(DEFAULTS, RuntimePolicy.from(packaged));
            assertEquals(LightningRuntimePolicy.from(new YamlConfiguration()),
                    LightningRuntimePolicy.from(packaged));
        }
    }

    @Test
    void meaningCategoryKeysAreRead() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("connection.command_queue_capacity", 11);
        config.set("connection.response_queue_capacity", 12);
        config.set("events.ring_capacity", 13);
        config.set("events.ring_bytes", 14);
        config.set("events.poll_default", 15);
        config.set("events.poll_limit", 16);
        config.set("entities.handle_capacity", 17);
        config.set("particles.max_count", 18);
        config.set("work.per_request", 19);
        config.set("work.per_session_tick", 20);
        config.set("work.per_player_tick", 21);
        config.set("work.global_per_tick", 22);

        assertEquals(new RuntimePolicy(13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 11, 12),
                RuntimePolicy.from(config));
    }

    @Test
    void legacyB5KeysAreReadOnlyWhenTheNewKeyIsAbsent() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("b5.event_ring_capacity", 99);
        config.set("b5.connection_queue_capacity", 77);
        config.set("connection.command_queue_capacity", 55);

        RuntimePolicy policy = RuntimePolicy.from(config);

        assertEquals(99, policy.eventRingCapacity());
        assertEquals(55, policy.connectionQueueCapacity(), "new key wins over the legacy key");
        assertEquals(DEFAULTS.maxParticleCount(), policy.maxParticleCount());
    }

    @Test
    void pollDefaultIsCappedByLimitAndNonPositiveValuesClampToOne() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("events.poll_default", 100);
        config.set("events.poll_limit", 32);
        config.set("particles.max_count", 0);

        RuntimePolicy policy = RuntimePolicy.from(config);

        assertEquals(32, policy.eventPollDefault());
        assertEquals(32, policy.eventPollLimit());
        assertEquals(1, policy.maxParticleCount());
    }
}
