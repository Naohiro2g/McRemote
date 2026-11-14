package club.code2create.mcremote;

import org.bukkit.Location;

import java.util.Map;

/** Minimal session surface used by world command handlers. */
interface WorldCommandContext {
    Location getOrigin();

    boolean hasConstructionPermission();

    boolean isWithinBuildRange(Location target);

    int getBuildRange();

    WorkAdmission.Result admitWork(int units);

    default void recordParticleWorkBackpressure() { }

    void respondResult(Object value);

    void respondError(int code, String reason, Map<String, Object> extraData);
}
