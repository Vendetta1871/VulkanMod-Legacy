package net.vesu.lod;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes dirty sections (AUDIT 5.5). A queued section holds a reference, so it stays
 * resident until written; dirty data is never dropped. Batches go in one write transaction.
 */
public final class SaveService implements WorkerPool.Service {
    public static final int BLOCKING_LIMIT = 5000;
    private static final int BATCH = 64;

    private final LodWorld world;
    private final ConcurrentLinkedQueue<WorldSection> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();

    SaveService(LodWorld world) {
        this.world = world;
    }

    /** Queues a dirty section unless already queued. False if the section was freed meanwhile. */
    boolean enqueue(WorldSection s) {
        if (!s.queuedForSave.compareAndSet(false, true)) return true;
        if (!s.tryAcquire()) {
            s.queuedForSave.set(false);
            return false;
        }
        queue.add(s);
        size.incrementAndGet();
        WorkerPool.signalAll();
        return true;
    }

    void markDirty(WorldSection s) {
        s.dirty.set(true);
        enqueue(s);
    }

    public int queued() {
        return size.get();
    }

    @Override
    public int weight() {
        return 100;
    }

    @Override
    public boolean hasWork() {
        return size.get() > 0;
    }

    @Override
    public boolean runOne() {
        long[] keys = new long[BATCH];
        byte[][] blobs = new byte[BATCH][];
        WorldSection[] taken = new WorldSection[BATCH];
        int n = 0;
        while (n < BATCH) {
            WorldSection s = queue.poll();
            if (s == null) break;
            size.decrementAndGet();
            s.queuedForSave.set(false);
            taken[n] = s;
            if (s.dirty.getAndSet(false)) {
                try {
                    blobs[n] = SectionCodec.serialize(s.key, s.data, s.childMask());
                    keys[n] = s.key;
                } catch (RuntimeException e) {
                    LodWorld.LOG.accept("LOD section " + s + " failed to serialize: " + e);
                }
            }
            n++;
        }
        if (n == 0) return false;
        try {
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (blobs[i] == null) continue;
                keys[m] = keys[i];
                blobs[m++] = blobs[i];
            }
            if (m > 0) world.storage.putSections(keys, blobs, m);
        } catch (RuntimeException e) {
            world.fail(e);
        } finally {
            for (int i = 0; i < n; i++) taken[i].release();
        }
        return true;
    }

    /** Writes everything queued on the calling thread. */
    void drain() {
        while (runOne()) {
            // keep going
        }
    }
}
