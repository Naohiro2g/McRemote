package club.code2create.mcremote;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 意味別 category の config key と、旧 release 番号 category（{@code b5:}／{@code b7:}）の対応
 * （DECISIONS 2026-09-03-02）。起動時の {@link #migrate} が旧 key の値を新 key へ移して旧 key を
 * 消すので、運用者が変えていた値は既定値に戻らない。読み込み時の読み替えは、移行を保存できなかった
 * 場合の保険として残す。どちらも初回 stable release の前に外す。
 */
final class LegacyConfigKeys {
    private static final Logger LOGGER = Logger.getLogger("McRemote");

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

    /** operator が書いた値だけを見る。同梱 config の既定値は「書かれていない」として扱う。 */
    static int getInt(ConfigurationSection config, String path, int fallback) {
        if (config.contains(path, true)) {
            return config.getInt(path, fallback);
        }
        String legacyPath = LEGACY_PATHS.get(path);
        if (legacyPath != null && config.contains(legacyPath, true)) {
            LOGGER.warning("config.yml: '" + legacyPath + "' is deprecated; move it to '" + path
                    + "'. The old key is still read for now but will be ignored from the stable release.");
            return config.getInt(legacyPath, fallback);
        }
        return fallback;
    }

    /** What {@link #migrate} changed, for logging. */
    record Migration(List<String> added, List<String> moved, List<String> removed) {
        boolean changed() {
            return !added.isEmpty() || !moved.isEmpty() || !removed.isEmpty();
        }
    }

    /**
     * Adds missing keys from the packaged defaults. A missing new key with a legacy counterpart takes
     * the legacy value instead of the default. Legacy keys are then removed: moved ones, and ones
     * whose new key the operator already set (the new key wins). Empty b5／b7 sections go too.
     */
    static Migration migrate(ConfigurationSection config, Configuration defaults) {
        List<String> added = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        if (defaults != null) {
            for (String path : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(path) || config.contains(path, true)) {
                    continue;
                }
                String legacyPath = LEGACY_PATHS.get(path);
                if (legacyPath != null && config.contains(legacyPath, true)) {
                    config.set(path, config.get(legacyPath));
                    moved.add(legacyPath + " -> " + path);
                } else {
                    config.set(path, defaults.get(path));
                    added.add(path);
                }
            }
        }
        for (Map.Entry<String, String> entry : LEGACY_PATHS.entrySet()) {
            String legacyPath = entry.getValue();
            if (config.contains(legacyPath, true) && config.contains(entry.getKey(), true)) {
                if (!moved.contains(legacyPath + " -> " + entry.getKey())) {
                    removed.add(legacyPath + " (ignored; " + entry.getKey() + " is set)");
                }
                config.set(legacyPath, null);
            }
        }
        for (String section : List.of("b7.lightning", "b7", "b5")) {
            ConfigurationSection legacy = config.getConfigurationSection(section);
            if (legacy != null && legacy.getKeys(false).isEmpty()) {
                config.set(section, null);
            }
        }
        for (String path : PreAuthPolicy.RETIRED_CONFIG_KEYS) {
            if (config.contains(path, true)) {
                config.set(path, null);
                removed.add(path + " (replaced by the max-players connection profile)");
            }
        }
        return new Migration(added, moved, removed);
    }
}
