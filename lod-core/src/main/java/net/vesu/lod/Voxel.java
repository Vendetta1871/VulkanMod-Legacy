package net.vesu.lod;

/**
 * One LOD voxel packed in a {@code long} (AUDIT 5.3).
 * <ul>
 *   <li>bits 56..63: light, {@code block << 4 | sky};</li>
 *   <li>bits 47..55: biome id (9 bits);</li>
 *   <li>bits 27..46: state id (20 bits), 0 = air;</li>
 *   <li>bits 0..26: zero.</li>
 * </ul>
 * Fields are read by shift and mask, never by decoding the whole word.
 */
public final class Voxel {
    public static final int LIGHT_SHIFT = 56, BIOME_SHIFT = 47, STATE_SHIFT = 27;
    public static final long STATE_MASK = 0xFFFFFL << STATE_SHIFT;
    public static final long BIOME_MASK = 0x1FFL << BIOME_SHIFT;
    public static final long LIGHT_MASK = 0xFFL << LIGHT_SHIFT;
    public static final int MAX_STATE = 0xFFFFF, MAX_BIOME = 0x1FF;

    /** Air under the open sky: what a section never stored holds. */
    public static final long AIR_SKYLIT = of(0, 0, 0x0F);

    private Voxel() {}

    public static long of(int state, int biome, int light) {
        return (long) (light & 0xFF) << LIGHT_SHIFT | (long) (biome & MAX_BIOME) << BIOME_SHIFT
                | (long) (state & MAX_STATE) << STATE_SHIFT;
    }

    public static int state(long v) {
        return (int) (v >>> STATE_SHIFT) & MAX_STATE;
    }

    public static int biome(long v) {
        return (int) (v >>> BIOME_SHIFT) & MAX_BIOME;
    }

    public static int light(long v) {
        return (int) (v >>> LIGHT_SHIFT) & 0xFF;
    }

    public static int blockLight(long v) {
        return (int) (v >>> (LIGHT_SHIFT + 4)) & 0xF;
    }

    public static int skyLight(long v) {
        return (int) (v >>> LIGHT_SHIFT) & 0xF;
    }

    public static boolean isAir(long v) {
        return (v & STATE_MASK) == 0;
    }

    public static long withLight(long v, int light) {
        return (v & ~LIGHT_MASK) | (long) (light & 0xFF) << LIGHT_SHIFT;
    }
}
