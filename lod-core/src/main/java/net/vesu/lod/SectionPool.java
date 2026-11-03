package net.vesu.lod;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/** Global free list of voxel arrays (AUDIT 5.3): about 100 MB kept, no GC churn. */
public final class SectionPool {
    public static final int KEEP = 400;
    private static final ConcurrentLinkedDeque<long[]> FREE = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger FREE_COUNT = new AtomicInteger();

    private SectionPool() {}

    public static long[] take() {
        long[] a = FREE.pollFirst();
        if (a != null) {
            FREE_COUNT.decrementAndGet();
            return a;
        }
        return new long[WorldSection.SIZE];
    }

    public static void give(long[] a) {
        if (a == null || a.length != WorldSection.SIZE) return;
        if (FREE_COUNT.incrementAndGet() > KEEP) {
            FREE_COUNT.decrementAndGet();
            return;
        }
        FREE.addFirst(a);
    }

    public static int free() {
        return FREE_COUNT.get();
    }
}
