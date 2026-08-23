package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Connection-epoch scoped opaque entity handles. */
final class EntityHandleRegistry {
    static final String PREFIX = "mceh_";
    static final String DIMENSION_CHANGED_REASON = "entity_dimension_changed";

    private final int capacity;
    private final SecureRandom random;
    private final Function<UUID, Entity> entityLookup;
    private final Map<String, Entry> byHandle = new HashMap<>();
    private final Map<UUID, String> byEntity = new HashMap<>();
    private int reservations;

    EntityHandleRegistry(int capacity) {
        this(capacity, new SecureRandom(), Bukkit::getEntity);
    }

    EntityHandleRegistry(int capacity, SecureRandom random) {
        this(capacity, random, Bukkit::getEntity);
    }

    EntityHandleRegistry(int capacity, SecureRandom random, Function<UUID, Entity> entityLookup) {
        if (capacity < 1) {
            throw new IllegalArgumentException("handle capacity must be positive");
        }
        this.capacity = capacity;
        this.random = random;
        this.entityLookup = entityLookup;
    }

    synchronized Reservation reserve() {
        if (byHandle.size() + reservations >= capacity) {
            throw new CapacityException();
        }
        reservations++;
        return new Reservation(newHandle());
    }

    synchronized String issue(Entity entity) {
        if (entity instanceof Player) {
            throw new IllegalArgumentException("players do not receive entity handles");
        }
        String existing = byEntity.get(entity.getUniqueId());
        if (existing != null) {
            return existing;
        }
        Reservation reservation = reserve();
        return reservation.commit(entity);
    }

    synchronized ResolveResult resolve(String handle) {
        Entry entry = byHandle.get(handle);
        if (entry == null) {
            return new ResolveResult(ResolveStatus.NOT_FOUND, null);
        }
        Entity entity = entityLookup.apply(entry.entityId());
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return new ResolveResult(ResolveStatus.REMOVED_OR_UNLOADED, null);
        }
        if (!entry.dimension().equals(DimensionResolver.canonical(entity.getWorld()))) {
            return new ResolveResult(ResolveStatus.DIMENSION_CHANGED, null);
        }
        return new ResolveResult(ResolveStatus.ACTIVE, entity);
    }

    synchronized void clear() {
        byHandle.clear();
        byEntity.clear();
        reservations = 0;
    }

    synchronized int size() {
        return byHandle.size();
    }

    final class Reservation implements AutoCloseable {
        private final String handle;
        private boolean open = true;

        private Reservation(String handle) {
            this.handle = handle;
        }

        synchronized String commit(Entity entity) {
            synchronized (EntityHandleRegistry.this) {
                if (!open) {
                    throw new IllegalStateException("reservation is closed");
                }
                if (entity instanceof Player) {
                    throw new IllegalArgumentException("players do not receive entity handles");
                }
                String existing = byEntity.get(entity.getUniqueId());
                if (existing != null) {
                    close();
                    return existing;
                }
                byHandle.put(handle, new Entry(entity.getUniqueId(), DimensionResolver.canonical(entity.getWorld())));
                byEntity.put(entity.getUniqueId(), handle);
                reservations--;
                open = false;
                return handle;
            }
        }

        @Override
        public void close() {
            synchronized (EntityHandleRegistry.this) {
                if (open) {
                    reservations--;
                    open = false;
                }
            }
        }
    }

    enum ResolveStatus { ACTIVE, NOT_FOUND, REMOVED_OR_UNLOADED, DIMENSION_CHANGED }

    record ResolveResult(ResolveStatus status, Entity entity) {
    }

    static final class CapacityException extends RuntimeException {
    }

    private String newHandle() {
        byte[] bytes = new byte[16];
        String handle;
        do {
            random.nextBytes(bytes);
            handle = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (byHandle.containsKey(handle));
        return handle;
    }

    private record Entry(UUID entityId, String dimension) {
    }
}
