package net.vesu.lod;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 32^3 voxels at one level (AUDIT 5.3), index {@code y<<10 | z<<5 | x}. Residency is one
 * atomic int: bit 0 loaded, bits 1+ the reference count.
 */
public final class WorldSection {
    public static final int SIZE = 32 * 32 * 32;

    public final long key;
    public final int lvl, x, y, z;
    /** Replaced, never resized; readers may see it unsynchronized while they hold a reference. */
    public volatile long[] data;
    final AtomicInteger state = new AtomicInteger();
    /** Threads waiting for this placeholder to be published (AUDIT 5.4 acquire, step 2). */
    int preAcquire;
    /** Level-0 non-air count. */
    final AtomicInteger nonAir = new AtomicInteger();
    final AtomicInteger nonEmptyChildren = new AtomicInteger();
    final AtomicBoolean dirty = new AtomicBoolean();
    final AtomicBoolean queuedForSave = new AtomicBoolean();
    final LodWorld world;

    public static final int NO_CHANGE = 0, MINOR_CHANGE = 1, MAJOR_CHANGE = 2;

    WorldSection(LodWorld world, long key) {
        this.world = world;
        this.key = key;
        this.lvl = SectionKey.lvl(key);
        this.x = SectionKey.x(key);
        this.y = SectionKey.y(key);
        this.z = SectionKey.z(key);
    }

    public static int index(int x, int y, int z) {
        return y << 10 | z << 5 | x;
    }

    public boolean loaded() {
        return (state.get() & 1) != 0;
    }

    public int refCount() {
        return state.get() >>> 1;
    }

    /** Adds a reference unless the section was freed meanwhile. */
    boolean tryAcquire() {
        while (true) {
            int s = state.get();
            if ((s & 1) == 0) return false;
            if (state.compareAndSet(s, s + 2)) return true;
        }
    }

    /** Takes one more reference on a section the caller already holds. */
    public WorldSection retain() {
        state.addAndGet(2);
        return this;
    }

    public void release() {
        world.cache.release(this);
    }

    public int childMask() {
        return nonEmptyChildren.get();
    }

    public boolean isEmpty() {
        return nonEmptyChildren.get() == 0;
    }

    public int nonAirCount() {
        return nonAir.get();
    }

    public boolean isDirty() {
        return dirty.get();
    }

    /** Level 0: the mask is all 8 bits or none, from the non-air count. */
    int setLevel0Count(int count) {
        nonAir.set(count);
        return setMask(count > 0 ? 0xFF : 0);
    }

    int setChild(int octant, boolean nonEmpty) {
        while (true) {
            int m = nonEmptyChildren.get();
            int n = nonEmpty ? m | 1 << octant : m & ~(1 << octant);
            if (n == m) return NO_CHANGE;
            if (nonEmptyChildren.compareAndSet(m, n)) return (m == 0) != (n == 0) ? MAJOR_CHANGE : MINOR_CHANGE;
        }
    }

    int setMask(int mask) {
        int m = nonEmptyChildren.getAndSet(mask);
        if (m == mask) return NO_CHANGE;
        return (m == 0) != (mask == 0) ? MAJOR_CHANGE : MINOR_CHANGE;
    }

    @Override
    public String toString() {
        return SectionKey.toString(key) + " refs=" + refCount() + (loaded() ? "" : " unloaded");
    }
}
