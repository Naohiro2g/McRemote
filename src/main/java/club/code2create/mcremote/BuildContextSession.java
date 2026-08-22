package club.code2create.mcremote;

import org.bukkit.Location;

import java.util.Map;

/** Minimal session surface used by protocol 22 build-context commands. */
interface BuildContextSession {
    Location getOrigin();

    void setOrigin(Location origin);

    void respondResult(Object value);

    void respondError(int code, String reason, Map<String, Object> extraData);
}
