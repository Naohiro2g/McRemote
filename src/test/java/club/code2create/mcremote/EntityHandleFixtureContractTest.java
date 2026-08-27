package club.code2create.mcremote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture-driven contract test against scratch-editor's events-v23.json (same fixed SHA as
 * SignFixtureContractTest/EventsFixtureContractTest): its projectile_targets.entity.handle example
 * documents the protocol 23 mcr_eh_ handle shape shared with the Scratch client.
 */
class EntityHandleFixtureContractTest {
    private static final String FIXTURE = "/fixtures/events-v23.json";

    // B6-H01: mcr_eh_ issuance shares the fixture's documented prefix and matches the production shape.
    @Test
    void b6H01IssuedHandleSharesFixturePrefixAndMatchesProductionShape() {
        String fixtureExample = fixture().getAsJsonObject("projectile_targets")
                .getAsJsonObject("entity").get("handle").getAsString();
        assertTrue(fixtureExample.startsWith(EntityHandleRegistry.PREFIX),
                "fixture example handle must use the same prefix McRemote issues");

        EntityHandleRegistry registry = new EntityHandleRegistry(1);
        String issued = registry.issue(entity(UUID.randomUUID(), "minecraft:overworld"));
        assertTrue(issued.matches("^" + Pattern.quote(EntityHandleRegistry.PREFIX) + "[A-Za-z0-9_-]{22}$"));
    }

    // B6-H02: a syntactically protocol-22-style (mceh_) handle is rejected as NOT_FOUND, not resolved.
    @Test
    void b6H02LegacyProtocol22PrefixHandleIsRejected() {
        EntityHandleRegistry registry = new EntityHandleRegistry(1);
        String issued = registry.issue(entity(UUID.randomUUID(), "minecraft:overworld"));
        String legacyStyle = "mceh_" + issued.substring(EntityHandleRegistry.PREFIX.length());

        assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND, registry.resolve(legacyStyle).status());
    }

    // B6-H03: the handle is an opaque token, not an encoding of the entity/dimension identity.
    @Test
    void b6H03HandleIsOpaqueAndDoesNotEncodeEntityOrDimensionIdentity() {
        UUID entityId = UUID.randomUUID();
        EntityHandleRegistry registry = new EntityHandleRegistry(1);
        String issued = registry.issue(entity(entityId, "minecraft:overworld"));

        assertFalse(issued.contains(entityId.toString()), "handle must not embed the entity UUID");
        assertFalse(issued.toLowerCase().contains("overworld"), "handle must not embed the dimension");
        assertEquals(EntityHandleRegistry.PREFIX.length() + 22, issued.length());
    }

    private static JsonObject fixture() {
        try (var reader = new InputStreamReader(
                EntityHandleFixtureContractTest.class.getResourceAsStream(FIXTURE), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Entity entity(UUID uuid, String dimension) {
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
