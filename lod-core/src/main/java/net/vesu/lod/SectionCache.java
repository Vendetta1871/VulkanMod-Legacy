package net.vesu.lod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Section cache of one LOD world (AUDIT 5.4). The primary level is 64 shards, each a
 * {@code key -> section} map behind its own read/write lock. Freed sections go to a
 * secondary LRU that keeps their arrays, so a quick re-acquire skips the disk.
 */
final class SectionCache {
    private static final int SHARDS = 64;

    private static final class Shard {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        final LongMap<WorldSection> map = new LongMap<>(256);
    }

    private final LodWorld world;
    private final Shard[] shards = new Shard[SHARDS];
    private final Map<Long, WorldSection> secondary;
    private final int secondaryCap;

    SectionCache(LodWorld world) {
        this.world = world;
        for (int i = 0; i < SHARDS; i++) shards[i] = new Shard();
        secondaryCap = Runtime.getRuntime().maxMemory() >= (4L << 30) ? 2048 : 1024;
        secondary = new LinkedHashMap<>(secondaryCap * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, WorldSection> eldest) {
                if (size() <= secondaryCap) return false;
                SectionPool.give(eldest.getValue().data);
                return true;
            }
        };
    }

    private Shard shard(long key) {
        return shards[(int) SectionKey.mix(key) & (SHARDS - 1)];
    }

    WorldSection acquire(long key) {
        Shard s = shard(key);
        while (true) {
            WorldSection h;
            s.lock.readLock().lock();
            try {
                h = s.map.get(key);
                if (h != null && h.tryAcquire()) return h;
            } finally {
                s.lock.readLock().unlock();
            }
            boolean loader = false;
            s.lock.writeLock().lock();
            try {
                h = s.map.get(key);
                if (h == null) {
                    h = new WorldSection(world, key);
                    s.map.put(key, h);
                    loader = true;
                } else if (h.loaded()) {
                    if (h.tryAcquire()) return h;
                    continue;
                } else {
                    // A placeholder another thread is loading: our reference is counted at publish.
                    h.preAcquire++;
                }
            } finally {
                s.lock.writeLock().unlock();
            }
            if (loader) {
                load(h);
                s.lock.writeLock().lock();
                try {
                    h.state.set(1 | (1 + h.preAcquire) << 1);
                    h.preAcquire = 0;
                } finally {
                    s.lock.writeLock().unlock();
                }
                return h;
            }
            while (!h.loaded()) Thread.onSpinWait();
            return h;
        }
    }

    private void load(WorldSection h) {
        WorldSection old;
        synchronized (secondary) {
            old = secondary.remove(h.key);
        }
        if (old != null) {
            h.data = old.data;
            h.nonEmptyChildren.set(old.nonEmptyChildren.get());
            h.nonAir.set(old.nonAir.get());
            return;
        }
        long[] data = SectionPool.take();
        SectionCodec.Header header = null;
        try {
            header = world.storage.getSection(h.key, b -> SectionCodec.deserialize(b, data));
            if (header != null && header.key() != h.key) {
                LodWorld.LOG.accept("LOD section " + SectionKey.toString(h.key) + " holds key "
                        + SectionKey.toString(header.key()) + "; treated as air");
                header = null;
            }
        } catch (RuntimeException e) {
            LodWorld.LOG.accept("LOD section " + SectionKey.toString(h.key) + " failed to load: " + e);
            header = null;
        }
        if (header == null) {
            java.util.Arrays.fill(data, Voxel.AIR_SKYLIT);
            h.nonEmptyChildren.set(0);
            h.nonAir.set(0);
        } else {
            h.nonEmptyChildren.set(header.childMask());
            if (h.lvl == 0) {
                int n = 0;
                for (long v : data) if (!Voxel.isAir(v)) n++;
                h.nonAir.set(n);
                h.nonEmptyChildren.set(n > 0 ? 0xFF : 0);
            }
        }
        h.data = data;
    }

    void release(WorldSection h) {
        int v = h.state.addAndGet(-2);
        if (v != 1) {
            if (v < 0 || (v & 1) == 0) throw new IllegalStateException("LOD section over-released: " + h);
            return;
        }
        if (h.dirty.get() && world.save.enqueue(h)) return;
        Shard s = shard(h.key);
        s.lock.writeLock().lock();
        try {
            if (!h.state.compareAndSet(1, 0)) return;
            s.map.remove(h.key);
        } finally {
            s.lock.writeLock().unlock();
        }
        if (h.dirty.get()) {
            LodWorld.LOG.accept("LOD section " + h + " freed while dirty");
        }
        synchronized (secondary) {
            WorldSection prev = secondary.put(h.key, h);
            if (prev != null && prev != h) SectionPool.give(prev.data);
        }
    }

    int primarySize() {
        int n = 0;
        for (Shard s : shards) {
            s.lock.readLock().lock();
            try {
                n += s.map.size();
            } finally {
                s.lock.readLock().unlock();
            }
        }
        return n;
    }

    int secondarySize() {
        synchronized (secondary) {
            return secondary.size();
        }
    }

    void clearSecondary() {
        synchronized (secondary) {
            for (WorldSection w : secondary.values()) SectionPool.give(w.data);
            secondary.clear();
        }
    }
}
