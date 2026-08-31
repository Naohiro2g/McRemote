package club.code2create.mcremote;

import org.bukkit.Location;

import java.util.Map;
import java.util.UUID;

/** Minimal session surface needed by the protocol 23.1 command handlers. */
interface B7CommandContext {
    UUID getBoundUuid();

    UUID getConnectionEpoch();

    Location getOrigin();

    boolean hasConstructionPermission();

    boolean isWithinBuildRange(Location target);

    WorkAdmission.Result admitWork(int units);

    boolean admitSetterWork(long units);

    boolean rejectTemporaryBackpressure();

    void respondResult(Object value);

    void respondError(int code, String reason, Map<String, Object> extraData);
}
