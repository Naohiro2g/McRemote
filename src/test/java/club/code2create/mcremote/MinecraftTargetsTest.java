package club.code2create.mcremote;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftTargetsTest {
    @Test void packagedConfigAndDeclarationAgreeButLegacyOverrideIsPreserved() throws Exception {
        List<String> bundled = MinecraftTargets.bundled();
        assertEquals(List.of("1.21.11", "26.2"), bundled);
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            assertFalse(MinecraftTargets.differsFromConfig(config, bundled));
            config.set("supported_mc_versions", List.of("1.21.11"));
            assertTrue(MinecraftTargets.differsFromConfig(config, bundled));
            assertEquals(List.of("1.21.11"), config.getStringList("supported_mc_versions"));
            assertEquals(List.of("1.21.11", "26.2"), MinecraftTargets.bundled());
            config.set("supported_mc_versions", List.of());
            assertTrue(MinecraftTargets.differsFromConfig(config, bundled));
        }
    }
}
