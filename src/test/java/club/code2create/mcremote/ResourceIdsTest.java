package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** minecraft: is filled in, other namespaces must be explicit, output is canonical. */
class ResourceIdsTest {
    @Test
    void minecraftNamespaceIsFilledInAndExplicitNamespacesAreKept() {
        assertEquals("minecraft:flame", ResourceIds.parse("flame").toString());
        assertEquals("minecraft:flame", ResourceIds.parse("minecraft:flame").toString());
        assertEquals("minecraft:entity.cow.ambient", ResourceIds.parse("entity.cow.ambient").toString());
        assertEquals("example:thing", ResourceIds.parse("example:thing").toString());
    }

    @Test
    void invalidOrNonCanonicalInputIsRejected() {
        for (String bad : List.of("", ":flame", "flame:", "a:b:c", "Flame", "minecraft:Flame", "fla me")) {
            assertNull(ResourceIds.parse(bad), bad);
        }
        assertNull(ResourceIds.parse(null));
    }
}
