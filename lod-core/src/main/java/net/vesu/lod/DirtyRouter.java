package net.vesu.lod;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Routes world changes to the render side (AUDIT 5.4): only keys the node manager watches
 * produce events. Sixteen lock-sharded {@code key -> watched flags} maps.
 */
public final class DirtyRouter {
    public interface Listener {
        /** The child-existence mask of a watched section changed. */
        void onStructure(long key);

        /** A watched section needs a new mesh. */
        void onRemesh(long key);
    }

    private static final int SHARDS = 16;
    private final ReentrantLock[] locks = new ReentrantLock[SHARDS];
    @SuppressWarnings("unchecked")
    private final LongMap<int[]>[] maps = new LongMap[SHARDS];
    private final Listener listener;

    public DirtyRouter(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < SHARDS; i++) {
            locks[i] = new ReentrantLock();
            maps[i] = new LongMap<>(256);
        }
    }

    private int shard(long key) {
        return (int) (SectionKey.mix(key) >>> 7) & (SHARDS - 1);
    }

    /** Starts watching; a newly watched BLOCK fires a mesh build at once. */
    public void watch(long key, int flags) {
        int i = shard(key);
        boolean remesh;
        locks[i].lock();
        try {
            int[] w = maps[i].get(key);
            if (w == null) maps[i].put(key, w = new int[1]);
            remesh = (flags & LodWorld.BLOCK) != 0 && (w[0] & LodWorld.BLOCK) == 0;
            w[0] |= flags;
        } finally {
            locks[i].unlock();
        }
        if (remesh) listener.onRemesh(key);
    }

    public void unwatch(long key, int flags) {
        int i = shard(key);
        locks[i].lock();
        try {
            int[] w = maps[i].get(key);
            if (w == null || (w[0] & flags) != flags) {
                throw new IllegalStateException("LOD unwatch without watch: " + SectionKey.toString(key) + " " + flags);
            }
            w[0] &= ~flags;
            if (w[0] == 0) maps[i].remove(key);
        } finally {
            locks[i].unlock();
        }
    }

    public boolean watched(long key, int flags) {
        int i = shard(key);
        locks[i].lock();
        try {
            int[] w = maps[i].get(key);
            return w != null && (w[0] & flags) != 0;
        } finally {
            locks[i].unlock();
        }
    }

    /** From the world (any thread). */
    public void dirty(long key, int flags) {
        int i = shard(key);
        int w;
        locks[i].lock();
        try {
            int[] e = maps[i].get(key);
            w = e == null ? 0 : e[0] & flags;
        } finally {
            locks[i].unlock();
        }
        if ((w & LodWorld.CHILD_EXISTENCE) != 0) listener.onStructure(key);
        if ((w & LodWorld.BLOCK) != 0) listener.onRemesh(key);
    }

    /** A manual rebuild, e.g. after model bakes. */
    public void remesh(long key) {
        if (watched(key, LodWorld.BLOCK)) listener.onRemesh(key);
    }

    public int size() {
        int n = 0;
        for (int i = 0; i < SHARDS; i++) {
            locks[i].lock();
            try {
                n += maps[i].size();
            } finally {
                locks[i].unlock();
            }
        }
        return n;
    }
}
