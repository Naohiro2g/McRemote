package club.code2create.mcremote;

import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * 意味別 category の config key を読む。旧 release 番号 category（{@code b5:}／{@code b7:}）の値は、
 * 新しい key が無い場合だけ読み替え、移動を促す警告を出す（DECISIONS 2026-09-03-02）。
 * 旧 key の読み替えは stable release で外す。
 */
final class LegacyConfigKeys {
    private static final Logger LOGGER = Logger.getLogger("McRemote");

    private LegacyConfigKeys() {
    }

    /** operator が書いた値だけを見る。同梱 config の既定値は「書かれていない」として扱う。 */
    static int getInt(ConfigurationSection config, String path, String legacyPath, int fallback) {
        if (config.contains(path, true)) {
            return config.getInt(path, fallback);
        }
        if (config.contains(legacyPath, true)) {
            LOGGER.warning("config.yml: '" + legacyPath + "' is deprecated; move it to '" + path
                    + "'. The old key is still read for now but will be ignored from the stable release.");
            return config.getInt(legacyPath, fallback);
        }
        return fallback;
    }
}
