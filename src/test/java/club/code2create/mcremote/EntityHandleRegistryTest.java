package club.code2create.mcremote;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityHandleRegistryTest {
    @Test
    void handleIsOpaqueStableWithinEpochAndCapacityBounded() {
        EntityHandleRegistry registry = new EntityHandleRegistry(1);
        Entity entity = entity(UUID.randomUUID(), "minecraft:overworld");

        String first = registry.issue(entity);
        String second = registry.issue(entity);

        assertEquals(first, second);
        assertTrue(first.matches("^mcr_eh_[A-Za-z0-9_-]{22}$"));
        assertEquals(1, registry.size());
        assertThrows(EntityHandleRegistry.CapacityException.class,
                () -> registry.issue(entity(UUID.randomUUID(), "minecraft:overworld")));
    }

    @Test
    void legacyProtocol22PrefixIsNotAValidHandle() {
        EntityHandleRegistry registry = new EntityHandleRegistry(1);
        Entity entity = entity(UUID.randomUUID(), "minecraft:overworld");
        String issued = registry.issue(entity);
        String legacyStyleHandle = "mceh_" + issued.substring(EntityHandleRegistry.PREFIX.length());

        assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND, registry.resolve(legacyStyleHandle).status());
    }

    @Test
    void abandonedReservationReleasesSlot() {
        EntityHandleRegistry registry = new EntityHandleRegistry(1);
        EntityHandleRegistry.Reservation reservation = registry.reserve();
        assertThrows(EntityHandleRegistry.CapacityException.class, registry::reserve);
        reservation.close();
        registry.reserve().close();
    }

    @Test
    void resolveDetectsDimensionKeyChange() {
        UUID uuid = UUID.randomUUID();
        Map<UUID, Entity> entities = new HashMap<>();
        Entity original = entity(uuid, "minecraft:overworld");
        entities.put(uuid, original);
        EntityHandleRegistry registry = new EntityHandleRegistry(
                2, new SecureRandom(), entities::get);
        String handle = registry.issue(original);

        assertEquals(EntityHandleRegistry.ResolveStatus.ACTIVE, registry.resolve(handle).status());
        entities.put(uuid, entity(uuid, "myworld:world"));
        assertEquals(EntityHandleRegistry.ResolveStatus.DIMENSION_CHANGED,
                registry.resolve(handle).status());
        assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND,
                registry.resolve(handle).status());
        assertEquals(0, registry.size());
        registry.reserve().close();
        assertEquals("entity_dimension_changed", EntityHandleRegistry.DIMENSION_CHANGED_REASON);
    }

    @Test
    void removedEntityReportsUnavailableOnceThenNotFoundAndReleasesCapacity() {
        UUID uuid = UUID.randomUUID();
        Map<UUID, Entity> entities = new HashMap<>();
        Entity original = entity(uuid, "minecraft:overworld");
        entities.put(uuid, original);
        EntityHandleRegistry registry = new EntityHandleRegistry(
                1, new SecureRandom(), entities::get);
        String handle = registry.issue(original);
        entities.remove(uuid);

        assertEquals(EntityHandleRegistry.ResolveStatus.REMOVED_OR_UNLOADED,
                registry.resolve(handle).status());
        assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND,
                registry.resolve(handle).status());
        assertEquals(0, registry.size());
        registry.reserve().close();
    }

    @Test
    void entityOutsideWorldIsUnavailableAndImmediatelyInvalidated() {
        UUID uuid = UUID.randomUUID();
        Entity original = entity(uuid, "minecraft:overworld");
        Entity unavailable = entity(uuid, "minecraft:overworld", false);
        EntityHandleRegistry registry = new EntityHandleRegistry(
                1, new SecureRandom(), ignored -> unavailable);
        String handle = registry.issue(original);

        assertEquals(EntityHandleRegistry.ResolveStatus.REMOVED_OR_UNLOADED,
                registry.resolve(handle).status());
        assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND,
                registry.resolve(handle).status());
    }

    private static Entity entity(UUID uuid, String dimension) {
        return entity(uuid, dimension, true);
    }

    private static Entity entity(UUID uuid, String dimension, boolean inWorld) {
        NamespacedKey key = NamespacedKey.fromString(dimension);
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getKey" -> key;
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
                    case "isValid" -> true;
                    case "isInWorld" -> inWorld;
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
