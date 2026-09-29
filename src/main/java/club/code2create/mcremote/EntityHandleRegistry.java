package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Connection-epoch scoped opaque entity handles. */
final class EntityHandleRegistry {
    static final String PREFIX = "mcr_eh_";
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
        Entity lookedUp = entityLookup.apply(entry.entityId());
        Entity entity = lookedUp != null ? lookedUp : entry.issuedEntity();
        String currentDimension = identifiableDimension(entity);
        if (currentDimension != null && !entry.dimension().equals(currentDimension)) {
            invalidate(handle, entry);
            return new ResolveResult(ResolveStatus.DIMENSION_CHANGED, null);
        }
        if (lookedUp == null || currentDimension == null
                || entity.isDead() || !entity.isValid() || !entity.isInWorld()) {
            invalidate(handle, entry);
            return new ResolveResult(ResolveStatus.REMOVED_OR_UNLOADED, null);
        }
        return new ResolveResult(ResolveStatus.ACTIVE, entity);
    }

    /**
     * B8 nearby の issueAll transaction（wire §5.8.3）。候補を順に処理し、同じdimensionの既存handleは
     * 再利用、dimensionが異なる既存handleは失効と新規発行をstageし、removed／unloaded／invalidの候補は
     * 除外して既存handleの失効をstageする。capacityはprojected stateで判定し、失敗時は何も変更しない。
     */
    synchronized List<Issued> issueAll(List<? extends Entity> candidates) {
        List<Issued> issued = new ArrayList<>();
        List<String> stagedInvalidations = new ArrayList<>();
        List<Entity> stagedNew = new ArrayList<>();
        for (Entity entity : candidates) {
            if (entity instanceof Player) {
                throw new IllegalArgumentException("players do not receive entity handles");
            }
            String existing = byEntity.get(entity.getUniqueId());
            String dimension = identifiableDimension(entity);
            boolean usable = dimension != null
                    && !entity.isDead() && entity.isValid() && entity.isInWorld();
            if (!usable) {
                if (existing != null) {
                    stagedInvalidations.add(existing);
                }
                continue;
            }
            if (existing != null && byHandle.get(existing).dimension().equals(dimension)) {
                issued.add(new Issued(existing, entity));
                continue;
            }
            if (existing != null) {
                stagedInvalidations.add(existing);
            }
            stagedNew.add(entity);
            issued.add(new Issued(null, entity));
        }
        if (!fitsCapacity(byHandle.size(), stagedInvalidations.size(), stagedNew.size(),
                reservations, capacity)) {
            throw new CapacityException();
        }
        for (String handle : stagedInvalidations) {
            invalidate(handle, byHandle.get(handle));
        }
        List<Issued> committed = new ArrayList<>(issued.size());
        for (Issued entry : issued) {
            if (entry.handle() != null) {
                committed.add(entry);
                continue;
            }
            Entity entity = entry.entity();
            String handle = newHandle();
            byHandle.put(handle, new Entry(
                    entity.getUniqueId(), DimensionResolver.canonical(entity.getWorld()), entity));
            byEntity.put(entity.getUniqueId(), handle);
            committed.add(new Issued(handle, entity));
        }
        return committed;
    }

    /** projected state = 現在のhandle数 − staged失効数 ＋ 新規数 ＋ open中のspawn reservation数 <= capacity。 */
    static boolean fitsCapacity(int current, int stagedRevokes, int newHandles, int reservations, int capacity) {
        return (long) current - stagedRevokes + newHandles + reservations <= capacity;
    }

    /** 成功した entity.setPose による dimension 移動を、同じ handle の issued dimension へ反映する。 */
    synchronized void updateDimension(String handle, Entity entity) {
        Entry entry = byHandle.get(handle);
        if (entry != null && entry.entityId().equals(entity.getUniqueId())) {
            byHandle.put(handle, new Entry(
                    entry.entityId(), DimensionResolver.canonical(entity.getWorld()), entity));
        }
    }

    /** entity.remove 成功時に handle を即時失効させる。 */
    synchronized void invalidate(String handle) {
        Entry entry = byHandle.get(handle);
        if (entry != null) {
            invalidate(handle, entry);
        }
    }

    record Issued(String handle, Entity entity) {
    }

    private static String identifiableDimension(Entity entity) {
        if (entity == null) {
            return null;
        }
        try {
            return entity.getWorld() == null ? null : DimensionResolver.canonical(entity.getWorld());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    synchronized void clear() {
        byHandle.clear();
        byEntity.clear();
        reservations = 0;
    }

    synchronized int size() {
        return byHandle.size();
    }

    private void invalidate(String handle, Entry entry) {
        byHandle.remove(handle, entry);
        byEntity.remove(entry.entityId(), handle);
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
                byHandle.put(handle, new Entry(
                        entity.getUniqueId(), DimensionResolver.canonical(entity.getWorld()), entity));
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

    private record Entry(UUID entityId, String dimension, Entity issuedEntity) {
    }
}
