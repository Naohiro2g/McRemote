package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/** Protocol 23.1 full, damage-capable lightning command. */
final class LightningCommands {
    static final int WORK_UNITS = 256;

    private final B7CommandContext session;
    private final LightningRateAdmission rateAdmission;
    private final LightningRuntimePolicy policy;

    LightningCommands(
            B7CommandContext session,
            LightningRateAdmission rateAdmission,
            LightningRuntimePolicy policy
    ) {
        this.session = session;
        this.rateAdmission = rateAdmission;
        this.policy = policy;
    }

    void register(CommandRegistry registry) {
        registry.registerStructured("world.strikeLightning", this::handleStrikeLightning);
    }

    void handleStrikeLightning(JsonElement params) {
        Location target;
        try {
            JsonArray args = WireParams.positional(params, 3);
            Location origin = session.getOrigin();
            if (origin == null || origin.getWorld() == null) {
                throw new IllegalArgumentException("origin is not set");
            }
            double x = origin.getX() + WireParams.finiteDouble(args, 0);
            double y = origin.getY() + WireParams.finiteDouble(args, 1);
            double z = origin.getZ() + WireParams.finiteDouble(args, 2);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("absolute lightning target must be finite");
            }
            target = new Location(origin.getWorld(), x, y, z);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }

        UUID playerId = session.getBoundUuid();
        if (playerId == null) {
            session.respondError(-32000, "auth_required", null);
            return;
        }
        if (!session.hasConstructionPermission()) {
            session.respondError(-32000, "permission_denied", null);
            return;
        }
        if (!session.isWithinBuildRange(target)) {
            session.respondError(-32000, "build_denied", null);
            return;
        }
        if (rateAdmission.admit(session.getConnectionEpoch(), playerId)
                == LightningRateAdmission.Result.BACKPRESSURE) {
            session.rejectTemporaryBackpressure();
            return;
        }

        WorkAdmission.Result work = session.admitWork(WORK_UNITS);
        if (work == WorkAdmission.Result.BACKPRESSURE) {
            session.rejectTemporaryBackpressure();
            return;
        }
        if (work == WorkAdmission.Result.WORK_LIMIT_EXCEEDED) {
            session.respondError(-32000, "work_limit_exceeded", null);
            return;
        }

        World world = target.getWorld();
        try {
            if (!WorldB5Commands.ensureChunkLoaded(
                    world, target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
                session.respondError(-32000, "backpressure", null);
                return;
            }
        } catch (Exception e) {
            session.respondError(-32000, "backpressure", null);
            return;
        }

        try {
            world.strikeLightning(target);
            session.respondResult(null);
        } catch (Exception e) {
            session.respondError(-32000, "internal_error", null);
        }
    }
}
