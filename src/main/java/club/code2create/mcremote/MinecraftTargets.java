package club.code2create.mcremote;

import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pre-build support declaration. Operator config cannot change this claim. */
final class MinecraftTargets {
    static List<String> bundled() {
        try (var input = Objects.requireNonNull(MinecraftTargets.class.getResourceAsStream("/minecraft-targets.json"));
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            if (!"mc-remote.minecraft-targets".equals(json.get("schema").getAsString())
                    || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Invalid target schema");
            var versions = new ArrayList<String>();
            for (var element : json.getAsJsonArray("minecraft_versions")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid target version");
                String version = element.getAsString();
                if (!version.matches("\\d+\\.\\d+(?:\\.\\d+)?") || versions.contains(version)) throw new IllegalArgumentException("Invalid target version");
                versions.add(version);
            }
            if (versions.isEmpty()) throw new IllegalArgumentException("Empty target declaration");
            return List.copyOf(versions);
        } catch (Exception e) {
            throw new IllegalStateException("Bundled Minecraft target declaration is invalid", e);
        }
    }

    static boolean differsFromConfig(ConfigurationSection config, List<String> bundled) {
        return !config.getStringList("supported_mc_versions").equals(bundled);
    }
}
