package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

import java.util.Objects;
import java.util.function.Function;

/** Protocol 22 DimensionRef parser and loaded-dimension resolver. */
final class DimensionResolver {
    static final String DEFAULT_DIMENSION = "minecraft:overworld";

    private final Function<NamespacedKey, World> loadedWorldLookup;

    DimensionResolver() {
        this(Bukkit::getWorld);
    }

    DimensionResolver(Function<NamespacedKey, World> loadedWorldLookup) {
        this.loadedWorldLookup = Objects.requireNonNull(loadedWorldLookup, "loadedWorldLookup");
    }

    ResolvedDimension resolve(String dimensionRef) {
        NamespacedKey key = parse(dimensionRef);
        return new ResolvedDimension(key, loadedWorldLookup.apply(key));
    }

    ResolvedDimension resolveDefault() {
        return resolve(DEFAULT_DIMENSION);
    }

    static NamespacedKey parse(String dimensionRef) {
        if (dimensionRef == null || dimensionRef.isEmpty()
                || dimensionRef.startsWith(":") || dimensionRef.endsWith(":")) {
            throw new IllegalArgumentException("invalid DimensionRef");
        }
        int colon = dimensionRef.indexOf(':');
        if (colon >= 0 && colon != dimensionRef.lastIndexOf(':')) {
            throw new IllegalArgumentException("invalid DimensionRef");
        }
        NamespacedKey key = NamespacedKey.fromString(dimensionRef);
        if (key == null) {
            throw new IllegalArgumentException("invalid DimensionRef");
        }
        return key;
    }

    static String canonical(World world) {
        return Objects.requireNonNull(world, "world").getKey().toString();
    }

    record ResolvedDimension(NamespacedKey key, World world) {
        String canonicalKey() {
            return key.toString();
        }

        boolean isLoaded() {
            return world != null;
        }
    }
}
