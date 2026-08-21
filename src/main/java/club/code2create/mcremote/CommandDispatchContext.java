package club.code2create.mcremote;

import org.bukkit.Location;

import java.util.Map;

/** Minimal session surface required by the command dispatcher. */
interface CommandDispatchContext {
    Location getOrigin();

    void respondError(int code, String reason, Map<String, Object> extraData);

    void close();
}
