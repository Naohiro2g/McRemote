package club.code2create.mcremote;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityHandleRegistryTest {
    @Test
    void handleIsOpaqueStableWithinEpochAndCapacityBounded() {
        EntityHandleRegistry registry = new EntityHandleRegistry(1);
        Entity entity = entity(UUID.randomUUID(), "world");

        String first = registry.issue(entity);
        String second = registry.issue(entity);

        assertEquals(first, second);
        assertTrue(first.matches("^mceh_[A-Za-z0-9_-]{22}$"));
        assertEquals(1, registry.size());
        assertThrows(EntityHandleRegistry.CapacityException.class,
                () -> registry.issue(entity(UUID.randomUUID(), "world")));
    }

    @Test
    void abandonedReservationReleasesSlot() {
        EntityHandleRegistry registry = new EntityHandleRegistry(1);
        EntityHandleRegistry.Reservation reservation = registry.reserve();
        assertThrows(EntityHandleRegistry.CapacityException.class, registry::reserve);
        reservation.close();
        registry.reserve().close();
    }

    private static Entity entity(UUID uuid, String worldName) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> worldName;
                    case "toString" -> "WorldTestProxy";
                    default -> defaultValue(method.getReturnType());
                });
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class<?>[]{Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getWorld" -> world;
                    case "isDead" -> false;
                    case "isValid", "isInWorld" -> true;
                    case "toString" -> "EntityTestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
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
