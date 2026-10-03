package club.code2create.mcremote;

import org.bukkit.NamespacedKey;

/**
 * Resource ID input rule shared by particle, entity and sound IDs: the {@code minecraft} namespace is
 * special and may be omitted (it is filled in), any other namespace must be written in full, and
 * outputs are always the canonical {@code namespace:path} form（DECISIONS 2026-06-26-03／2026-06-27-02,
 * the same rule as block and dimension IDs）。
 */
final class ResourceIds {
    private ResourceIds() {
    }

    /** Returns the canonical key, or null when the input is not a valid ID. */
    static NamespacedKey parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith(":") || raw.endsWith(":")) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon >= 0 && colon != raw.lastIndexOf(':')) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(raw);
        if (key == null) {
            return null;
        }
        // With an explicit namespace the input must already be canonical (no case folding etc.).
        if (colon >= 0 && !raw.equals(key.toString())) {
            return null;
        }
        // Without one, only the minecraft namespace is filled in and the path must be unchanged.
        if (colon < 0 && !key.toString().equals(NamespacedKey.MINECRAFT + ":" + raw)) {
            return null;
        }
        return key;
    }
}
