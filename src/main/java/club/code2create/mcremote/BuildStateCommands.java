package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Protocol 22 connection-local DimensionKey and build-origin commands. */
public class BuildStateCommands {
    static final int DEFAULT_ORIGIN_X = 200;
    static final int DEFAULT_ORIGIN_Y = 0;
    static final int DEFAULT_ORIGIN_Z = 200;

    private final BuildContextSession session;
    private final DimensionResolver dimensions;

    BuildStateCommands(BuildContextSession session, DimensionResolver dimensions) {
        this.session = session;
        this.dimensions = dimensions;
    }

    /** build.setOrigin [x,y,z] -> canonical build context. */
    public void handleSetOrigin(JsonElement params) {
        final JsonArray args;
        try {
            args = WireParams.positional(params, 3);
            int x = WireParams.integer(args, 0);
            int y = WireParams.integer(args, 1);
            int z = WireParams.integer(args, 2);
            Location current = session.getOrigin();
            if (current == null || current.getWorld() == null) {
                session.respondError(-32000, "origin_not_set", null);
                return;
            }
            Location updated = new Location(current.getWorld(), x, y, z);
            session.setOrigin(updated);
            session.respondResult(buildContext(updated));
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        }
    }

    /** build.setDimension [dimension_ref] -> canonical build context. */
    public void handleSetDimension(JsonElement params) {
        final String dimensionRef;
        try {
            JsonArray args = WireParams.positional(params, 1);
            dimensionRef = WireParams.string(args, 0);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }

        final DimensionResolver.ResolvedDimension resolved;
        try {
            resolved = dimensions.resolve(dimensionRef);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        if (!resolved.isLoaded()) {
            session.respondError(-32000, "unknown_dimension", dimensionData(resolved.canonicalKey()));
            return;
        }

        Location current = session.getOrigin();
        int x = current != null ? current.getBlockX() : DEFAULT_ORIGIN_X;
        int y = current != null ? current.getBlockY() : DEFAULT_ORIGIN_Y;
        int z = current != null ? current.getBlockZ() : DEFAULT_ORIGIN_Z;
        Location updated = new Location(resolved.world(), x, y, z);
        session.setOrigin(updated);
        session.respondResult(buildContext(updated));
    }

    /** Resolve hello.params.build atomically without mutating the session. */
    Location resolveHelloBuild(JsonElement helloParams) {
        if (helloParams == null || !helloParams.isJsonObject()) {
            throw new InvalidBuildException();
        }
        JsonElement buildElement = helloParams.getAsJsonObject().get("build");
        JsonObject build;
        if (buildElement == null) {
            build = new JsonObject();
        } else if (buildElement.isJsonObject()) {
            build = buildElement.getAsJsonObject();
        } else {
            throw new InvalidBuildException();
        }
        for (String key : build.keySet()) {
            if (!"dimension".equals(key) && !"origin".equals(key)) {
                throw new InvalidBuildException();
            }
        }

        World world;
        JsonElement dimensionElement = build.get("dimension");
        if (dimensionElement != null) {
            if (!dimensionElement.isJsonPrimitive()
                    || !dimensionElement.getAsJsonPrimitive().isString()) {
                throw new InvalidBuildException();
            }
            DimensionResolver.ResolvedDimension resolved;
            try {
                resolved = dimensions.resolve(dimensionElement.getAsString());
            } catch (IllegalArgumentException e) {
                throw new InvalidBuildException();
            }
            if (!resolved.isLoaded()) {
                throw new UnknownDimensionException(resolved.canonicalKey());
            }
            world = resolved.world();
        } else {
            Location current = session == null ? null : session.getOrigin();
            if (current != null && current.getWorld() != null) {
                world = current.getWorld();
            } else {
                DimensionResolver.ResolvedDimension resolved = dimensions.resolveDefault();
                if (!resolved.isLoaded()) {
                    throw new UnknownDimensionException(resolved.canonicalKey());
                }
                world = resolved.world();
            }
        }

        int x = DEFAULT_ORIGIN_X;
        int y = DEFAULT_ORIGIN_Y;
        int z = DEFAULT_ORIGIN_Z;
        Location current = session == null ? null : session.getOrigin();
        if (current != null) {
            x = current.getBlockX();
            y = current.getBlockY();
            z = current.getBlockZ();
        }
        JsonElement originElement = build.get("origin");
        if (originElement != null) {
            try {
                JsonArray origin = WireParams.positional(originElement, 3);
                x = WireParams.integer(origin, 0);
                y = WireParams.integer(origin, 1);
                z = WireParams.integer(origin, 2);
            } catch (IllegalArgumentException e) {
                throw new InvalidBuildException();
            }
        }
        return new Location(world, x, y, z);
    }

    Location defaultOrigin() {
        DimensionResolver.ResolvedDimension resolved = dimensions.resolveDefault();
        if (!resolved.isLoaded()) {
            return null;
        }
        return new Location(resolved.world(), DEFAULT_ORIGIN_X, DEFAULT_ORIGIN_Y, DEFAULT_ORIGIN_Z);
    }

    static Map<String, Object> buildContext(Location location) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("dimension", DimensionResolver.canonical(location.getWorld()));
        context.put("origin", List.of(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        return context;
    }

    static Map<String, Object> dimensionData(String dimension) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dimension", dimension);
        return data;
    }

    static final class InvalidBuildException extends IllegalArgumentException {
    }

    static final class UnknownDimensionException extends IllegalArgumentException {
        private final String dimension;

        UnknownDimensionException(String dimension) {
            this.dimension = dimension;
        }

        String dimension() {
            return dimension;
        }
    }
}
