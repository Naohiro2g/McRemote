package club.code2create.mcremote;

import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ParticleBuilderStage1Test {
    @Test
    void builderPreservesExistingWorldSpawnParticleArguments() {
        for (boolean force : new boolean[]{true, false}) {
            AtomicInteger spawnCalls = new AtomicInteger();
            World world = worldProxy(spawnCalls, force);
            Location location = new Location(world, 1.25, 64.5, -3.75);

            ParticleBuilder builder = WorldB5Commands.particleBuilder(
                    Particle.FLAME, location, 17, 0.1, 0.2, 0.3, 0.4, force);

            assertEquals(Particle.FLAME, builder.particle());
            assertEquals(location, builder.location());
            assertEquals(17, builder.count());
            assertEquals(0.1, builder.offsetX());
            assertEquals(0.2, builder.offsetY());
            assertEquals(0.3, builder.offsetZ());
            assertEquals(0.4, builder.extra());
            assertNull(builder.data());
            assertNull(builder.receivers());
            assertNull(builder.source());
            assertEquals(force, builder.force());

            builder.spawn();
            assertEquals(1, spawnCalls.get());
        }
    }

    private static World worldProxy(AtomicInteger spawnCalls, boolean expectedForce) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("spawnParticle") && args.length == 13) {
                        assertEquals(Particle.FLAME, args[0]);
                        assertNull(args[1], "receivers must remain world-wide");
                        assertNull(args[2], "source must remain unset");
                        assertEquals(1.25, args[3]);
                        assertEquals(64.5, args[4]);
                        assertEquals(-3.75, args[5]);
                        assertEquals(17, args[6]);
                        assertEquals(0.1, args[7]);
                        assertEquals(0.2, args[8]);
                        assertEquals(0.3, args[9]);
                        assertEquals(0.4, args[10]);
                        assertNull(args[11], "data must remain null");
                        assertEquals(expectedForce, args[12]);
                        spawnCalls.incrementAndGet();
                        return null;
                    }
                    return switch (method.getName()) {
                        case "toString" -> "ParticleBuilderStage1World";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
