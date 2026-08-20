package club.code2create.mcremote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connection-epoch scoped, non-destructive b5 event ring. */
final class EventRing {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final int maxEvents;
    private final int maxBytes;
    private final int maxResultBytes;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private int retainedBytes;
    private long latestSequence;
    private long overflowDroppedTotal;
    private long capacityDroppedTotal;
    private long explicitlyDiscardedTotal;

    EventRing(int maxEvents, int maxBytes, int maxResultBytes) {
        if (maxEvents < 1 || maxBytes < 1 || maxResultBytes < 256) {
            throw new IllegalArgumentException("event ring limits must be positive");
        }
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
        this.maxResultBytes = maxResultBytes;
    }

    synchronized boolean offer(Map<String, Object> capturedEvent) {
        long sequence = ++latestSequence;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sequence", sequence);
        event.putAll(capturedEvent);
        Map<String, Object> immutable = Collections.unmodifiableMap(new LinkedHashMap<>(event));
        int bytes = jsonBytes(immutable);
        if (bytes > maxBytes || bytes > maxResultBytes - 256) {
            capacityDroppedTotal++;
            return false;
        }
        while (!entries.isEmpty()
                && (entries.size() >= maxEvents || retainedBytes + bytes > maxBytes)) {
            Entry removed = entries.removeFirst();
            retainedBytes -= removed.bytes();
            overflowDroppedTotal++;
        }
        if (entries.size() >= maxEvents || retainedBytes + bytes > maxBytes) {
            capacityDroppedTotal++;
            return false;
        }
        entries.addLast(new Entry(sequence, immutable, bytes));
        retainedBytes += bytes;
        return true;
    }

    synchronized void dropForCapacity() {
        latestSequence++;
        capacityDroppedTotal++;
    }

    synchronized Map<String, Object> poll(long afterSequence, int requestedLimit) {
        if (afterSequence < 0 || afterSequence > latestSequence || requestedLimit < 1) {
            throw new IllegalArgumentException("invalid event cursor or limit");
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        long through = afterSequence;
        boolean limitReached = false;
        for (Entry entry : entries) {
            if (entry.sequence() <= afterSequence) {
                continue;
            }
            if (selected.size() >= requestedLimit) {
                limitReached = true;
                break;
            }
            selected.add(entry.event());
            through = entry.sequence();
        }
        if (!limitReached && selected.size() < requestedLimit) {
            through = latestSequence;
        }

        Map<String, Object> result = result(selected, through);
        while (!selected.isEmpty() && jsonBytes(result) > maxResultBytes) {
            selected.remove(selected.size() - 1);
            through = selected.isEmpty() ? afterSequence
                    : ((Number) selected.get(selected.size() - 1).get("sequence")).longValue();
            result = result(selected, through);
        }
        if (jsonBytes(result) > maxResultBytes) {
            throw new IllegalStateException("event poll metadata exceeds response admission");
        }
        return result;
    }

    synchronized void clear() {
        explicitlyDiscardedTotal += entries.size();
        entries.clear();
        retainedBytes = 0;
    }

    synchronized long latestSequence() {
        return latestSequence;
    }

    synchronized int retainedCount() {
        return entries.size();
    }

    private Map<String, Object> result(List<Map<String, Object>> selected, long through) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", List.copyOf(selected));
        result.put("through_sequence", through);
        result.put("latest_sequence", latestSequence);
        result.put("filtered_out", 0);
        result.put("overflow_dropped_total", overflowDroppedTotal);
        result.put("capacity_dropped_total", capacityDroppedTotal);
        result.put("explicitly_discarded_total", explicitlyDiscardedTotal);
        return result;
    }

    private static int jsonBytes(Object value) {
        return GSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private record Entry(long sequence, Map<String, Object> event, int bytes) {
    }
}
