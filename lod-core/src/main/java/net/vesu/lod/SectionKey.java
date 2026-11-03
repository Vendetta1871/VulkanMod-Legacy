package net.vesu.lod;

/**
 * World-section key (AUDIT 5.3): {@code lvl<<60 | (y&0xFF)<<52 | (z&0xFFFFFF)<<28 | (x&0xFFFFFF)<<4}.
 * Coordinates count sections of that level and are sign-extended on decode; the low four
 * bits are spare. A level-{@code lvl} section spans {@code 32 << lvl} blocks per axis.
 */
public final class SectionKey {
    public static final int MAX_LEVEL = 4;

    private SectionKey() {}

    public static long of(int lvl, int x, int y, int z) {
        return (long) lvl << 60 | (long) (y & 0xFF) << 52 | (long) (z & 0xFFFFFF) << 28 | (long) (x & 0xFFFFFF) << 4;
    }

    public static int lvl(long key) {
        return (int) (key >>> 60);
    }

    public static int x(long key) {
        return (int) (key << 36 >> 40);
    }

    public static int y(long key) {
        return (int) (key << 4 >> 56);
    }

    public static int z(long key) {
        return (int) (key << 12 >> 40);
    }

    public static long parent(long key) {
        return of(lvl(key) + 1, x(key) >> 1, y(key) >> 1, z(key) >> 1);
    }

    /** Child in octant {@code (x&1) | (z&1)<<1 | (y&1)<<2} (the emptiness-mask order). */
    public static long child(long key, int octant) {
        return of(lvl(key) - 1, x(key) << 1 | (octant & 1), y(key) << 1 | (octant >> 2 & 1),
                z(key) << 1 | (octant >> 1 & 1));
    }

    /** This section's bit in its parent's {@code nonEmptyChildren} mask. */
    public static int octantInParent(long key) {
        return (x(key) & 1) | (z(key) & 1) << 1 | (y(key) & 1) << 2;
    }

    /** Neighbour across face {@code f} (1.12 EnumFacing order: down, up, north, south, west, east). */
    public static long neighbor(long key, int face) {
        int x = x(key), y = y(key), z = z(key);
        switch (face) {
            case 0 -> y--;
            case 1 -> y++;
            case 2 -> z--;
            case 3 -> z++;
            case 4 -> x--;
            default -> x++;
        }
        return of(lvl(key), x, y, z);
    }

    /** Stafford variant 13: the shard and hash mixer. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    public static String toString(long key) {
        return "L" + lvl(key) + "[" + x(key) + "," + y(key) + "," + z(key) + "]";
    }
}
