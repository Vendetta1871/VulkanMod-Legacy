package net.vesu.lod.gpu;

import java.util.Map;
import java.util.TreeMap;

/** First-fit allocator of quad ranges in the arena, with coalescing frees. Render thread. */
final class QuadAllocator {
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private final long capacity;
    private long used;

    QuadAllocator(long capacity) {
        this.capacity = capacity;
        free.put(0L, capacity);
    }

    long used() {
        return used;
    }

    long capacity() {
        return capacity;
    }

    /** Start of a range of {@code n} quads, or -1. */
    long alloc(long n) {
        for (Map.Entry<Long, Long> e : free.entrySet()) {
            if (e.getValue() >= n) {
                long start = e.getKey(), len = e.getValue();
                free.remove(start);
                if (len > n) free.put(start + n, len - n);
                used += n;
                return start;
            }
        }
        return -1;
    }

    void free(long start, long n) {
        if (n <= 0) return;
        used -= n;
        Map.Entry<Long, Long> prev = free.floorEntry(start);
        if (prev != null && prev.getKey() + prev.getValue() == start) {
            start = prev.getKey();
            n += prev.getValue();
            free.remove(start);
        }
        Long next = free.get(start + n);
        if (next != null) {
            free.remove(start + n);
            n += next;
        }
        free.put(start, n);
    }
}
