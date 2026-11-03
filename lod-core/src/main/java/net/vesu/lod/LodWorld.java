package net.vesu.lod;

import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * One LOD world, per dimension (AUDIT 5.4): storage, registries and the section cache.
 * Chunk-section snapshots are inserted at all five levels; changes notify the save path and
 * the dirty listener with the update flags and a 6-bit neighbour-face mask.
 */
public final class LodWorld implements AutoCloseable {
    public static final int BLOCK = 1, CHILD_EXISTENCE = 2, DONT_SAVE = 4;
    public static final int MAX_LEVEL = SectionKey.MAX_LEVEL;

    /** Log sink for the core (the adapter routes it to the game log). */
    public static volatile Consumer<String> LOG = System.err::println;

    public interface DirtyListener {
        void onDirty(WorldSection section, int flags, int neighborMask);
    }

    final LodStorage storage;
    final SectionCache cache;
    final SaveService save;
    public final LodRegistry registry;
    private volatile DirtyListener listener;
    private volatile Consumer<Throwable> failure;
    private volatile boolean closed;

    public LodWorld(LodStorage storage, ToIntFunction<String> opacityOf) {
        this.storage = storage;
        this.registry = new LodRegistry(storage, opacityOf);
        this.cache = new SectionCache(this);
        this.save = new SaveService(this);
        WorkerPool.register(save);
    }

    public void setDirtyListener(DirtyListener listener) {
        this.listener = listener;
    }

    /** Called once when storage fails; the adapter turns LODs off for the session. */
    public void setFailureHandler(Consumer<Throwable> failure) {
        this.failure = failure;
    }

    void fail(Throwable t) {
        LOG.accept("LOD storage failed: " + t);
        Consumer<Throwable> f = failure;
        if (f != null) f.accept(t);
    }

    public WorldSection acquire(long key) {
        return cache.acquire(key);
    }

    public WorldSection acquire(int lvl, int x, int y, int z) {
        return cache.acquire(SectionKey.of(lvl, x, y, z));
    }

    public SaveService saveService() {
        return save;
    }

    public int primaryCached() {
        return cache.primarySize();
    }

    public int secondaryCached() {
        return cache.secondarySize();
    }

    private void notifyDirty(WorldSection ws, int flags, int neighbors) {
        if ((flags & DONT_SAVE) == 0) save.markDirty(ws);
        DirtyListener l = listener;
        if (l != null) l.onDirty(ws, flags & ~DONT_SAVE, neighbors);
    }

    /**
     * Inserts a chunk-section snapshot (AUDIT 5.4) at chunk-section (x, y, z). Runs on the
     * producer's thread. Each level 0..4 copies the snapshot's own mip level into its
     * sub-box; the walk stops at the first level where nothing changed and the emptiness
     * of the level below did not flip.
     */
    public void insert(SectionSnapshot snap) {
        boolean childFlipped = false;
        long childKey = 0;
        boolean childNonEmpty = false;
        for (int lvl = 0; lvl <= MAX_LEVEL; lvl++) {
            int sh = lvl + 1, n = 16 >> lvl;
            WorldSection ws = cache.acquire(SectionKey.of(lvl, snap.x >> sh, snap.y >> sh, snap.z >> sh));
            try {
                int ox = (snap.x & ((1 << sh) - 1)) * n;
                int oy = (snap.y & ((1 << sh) - 1)) * n;
                int oz = (snap.z & ((1 << sh) - 1)) * n;
                boolean changed;
                int emptiness = WorldSection.NO_CHANGE;
                synchronized (ws) {
                    long[] d = ws.data;
                    long[] src = snap.data;
                    int base = SectionSnapshot.OFFSET[lvl];
                    int oldNonAir = 0, newNonAir = 0;
                    boolean diff = false;
                    for (int y = 0; y < n; y++) {
                        for (int z = 0; z < n; z++) {
                            int si = base + (y * n + z) * n;
                            int di = WorldSection.index(ox, oy + y, oz + z);
                            for (int x = 0; x < n; x++) {
                                long o = d[di + x], v = src[si + x];
                                if (o != v) {
                                    diff = true;
                                    d[di + x] = v;
                                }
                                if (lvl == 0) {
                                    if (!Voxel.isAir(o)) oldNonAir++;
                                    if (!Voxel.isAir(v)) newNonAir++;
                                }
                            }
                        }
                    }
                    changed = diff;
                    if (lvl == 0) {
                        emptiness = ws.setLevel0Count(ws.nonAir.get() + newNonAir - oldNonAir);
                    } else if (childFlipped) {
                        emptiness = ws.setChild(SectionKey.octantInParent(childKey), childNonEmpty);
                    }
                }
                if (changed) {
                    int nb = 0;
                    if (oy == 0) nb |= 1;
                    if (oy + n == 32) nb |= 2;
                    if (oz == 0) nb |= 4;
                    if (oz + n == 32) nb |= 8;
                    if (ox == 0) nb |= 16;
                    if (ox + n == 32) nb |= 32;
                    notifyDirty(ws, BLOCK | (emptiness != WorldSection.NO_CHANGE ? CHILD_EXISTENCE : 0), nb);
                } else if (emptiness != WorldSection.NO_CHANGE) {
                    notifyDirty(ws, CHILD_EXISTENCE, 0);
                }
                childFlipped = emptiness == WorldSection.MAJOR_CHANGE;
                childKey = ws.key;
                childNonEmpty = !ws.isEmpty();
                if (!changed && !childFlipped) return;
            } finally {
                ws.release();
            }
        }
    }

    /** Writes everything dirty and syncs; the caller stops producers first. */
    public void flush() {
        save.drain();
        storage.flush();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        WorkerPool.unregister(save);
        try {
            save.drain();
        } finally {
            cache.clearSecondary();
            storage.close();
        }
    }

    public boolean closed() {
        return closed;
    }
}
