package club.code2create.mcremote;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意味別 category の config key と、旧 release 番号 category（{@code b5:}／{@code b7:}）の対応
 * （DECISIONS 2026-10-22-01）。旧 key は読み替え・移行せず、起動を止めるために検出する。
 * 対応表は運用者への修正案内にだけ使う。
 */
final class LegacyConfigKeys {
    /** new path → legacy path. */
    static final Map<String, String> LEGACY_PATHS = legacyPaths();

    private LegacyConfigKeys() {
    }

    private static Map<String, String> legacyPaths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("connection.command_queue_capacity", "b5.connection_queue_capacity");
        paths.put("connection.response_queue_capacity", "b5.connection_response_queue_capacity");
        paths.put("events.ring_capacity", "b5.event_ring_capacity");
        paths.put("events.ring_bytes", "b5.event_ring_bytes");
        paths.put("events.poll_default", "b5.event_poll_default");
        paths.put("events.poll_limit", "b5.event_poll_limit");
        paths.put("entities.handle_capacity", "b5.entity_handle_capacity");
        paths.put("particles.max_count", "b5.max_particle_count");
        paths.put("work.per_request", "b5.max_work_per_request");
        paths.put("work.per_session_tick", "b5.session_work_per_tick");
        paths.put("work.per_player_tick", "b5.player_work_per_tick");
        paths.put("work.global_per_tick", "b5.global_work_per_tick");
        for (String key : List.of("connection_cooldown_ticks", "player_cooldown_ticks",
                "global_per_tick", "rolling_window_ticks", "global_per_window")) {
            paths.put("lightning." + key, "b7.lightning." + key);
        }
        return java.util.Collections.unmodifiableMap(paths);
    }

    /** Lists operator-written obsolete keys, including unknown keys and empty sections. No mutation. */
    static List<String> errors(ConfigurationSection config) {
        List<String> errors = new ArrayList<>();
        for (String path : config.getKeys(true).stream().sorted().toList()) {
            if (!(path.equals("b5") || path.startsWith("b5.")
                    || path.equals("b7") || path.startsWith("b7.")) || !config.contains(path, true)) {
                continue;
            }
            ConfigurationSection section = config.getConfigurationSection(path);
            if (section != null && !section.getKeys(false).isEmpty()) {
                continue;
            }
            String replacement = LEGACY_PATHS.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(path))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            errors.add("config.yml: unsupported legacy key '" + path + "'. "
                    + (replacement == null ? "Remove this obsolete key/section."
                    : "Use '" + replacement + "' instead, then remove '" + path + "'."));
        }
        return List.copyOf(errors);
    }

    /** Ordinary default insertion and retired connection-profile cleanup, unrelated to b5/b7. */
    record Update(List<String> added, List<String> removed) {
        boolean changed() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }

    /** Adds missing packaged defaults, preserving explicitly set values. No b5/b7 migration. */
    static Update addMissingDefaults(ConfigurationSection config, Configuration defaults) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        if (defaults != null) {
            for (String path : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(path) || config.contains(path, true)) {
                    continue;
                }
                config.set(path, defaults.get(path));
                added.add(path);
            }
        }
        for (String path : PreAuthPolicy.RETIRED_CONFIG_KEYS) {
            if (config.contains(path, true)) {
                config.set(path, null);
                removed.add(path + " (replaced by the max-players connection profile)");
            }
        }
        return new Update(added, removed);
    }
}
