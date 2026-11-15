package club.code2create.mcremote;

import java.util.Map;
import org.bukkit.Location;

/** Diagnostics for the existing policy checks; does not replace their predicates. */
final class BuildPolicyErrors {
    private BuildPolicyErrors() {}

    static boolean range(WorldCommandContext session, Location... points) {
        for (Location point : points) {
            if (!session.isWithinBuildRange(point)) {
                Location origin = session.getOrigin();
                session.respondError(-32000, "build_range_exceeded", Map.of(
                        "bounds", Map.of("center", Map.of("x", origin.getX(), "z", origin.getZ()),
                                "build_range", session.getBuildRange()),
                        "violating", Map.of("x", point.getX(), "y", point.getY(), "z", point.getZ())));
                return false;
            }
        }
        return true;
    }

    static void blockCount(RemoteSession session, long requested) {
        session.respondError(-32000, "block_count_limit_exceeded", Map.of(
                "limit", session.getBuildBlockLimit(), "requested", requested));
    }
}
