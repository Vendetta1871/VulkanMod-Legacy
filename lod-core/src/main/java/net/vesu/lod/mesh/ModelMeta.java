package net.vesu.lod.mesh;

/**
 * Model metadata word (AUDIT 5.14). Per-face byte at {@code 8 * face} ({@code 0xFF} = no
 * face): bit 0 occludes neighbours, bit 1 covers the whole face, bit 2 can be occluded,
 * bit 3 self-lit. Global byte at bit 48: 1 biome-tinted, 2 translucent, 4 double-sided,
 * 8 contains fluid, 16 is fluid, 32 culls its own kind, 64 fully opaque. Light emission
 * at bits 55..58.
 */
public final class ModelMeta {
    public static final int F_OCCLUDES = 1, F_COVERS = 2, F_OCCLUDABLE = 4, F_SELF_LIT = 8, NO_FACE = 0xFF;
    public static final int TINTED = 1, TRANSLUCENT = 2, DOUBLE_SIDED = 4, HAS_FLUID = 8, IS_FLUID = 16,
            CULLS_OWN = 32, OPAQUE = 64;

    private ModelMeta() {}

    public static int face(long meta, int face) {
        return (int) (meta >>> (face * 8)) & 0xFF;
    }

    public static int flags(long meta) {
        return (int) (meta >>> 48) & 0x7F;
    }

    public static boolean has(long meta, int flag) {
        return (flags(meta) & flag) != 0;
    }

    public static int emission(long meta) {
        return (int) (meta >>> 55) & 0xF;
    }

    public static long of(int[] faces, int flags, int emission) {
        long m = 0;
        for (int f = 0; f < 6; f++) m |= (long) (faces[f] & 0xFF) << (f * 8);
        return m | (long) (flags & 0x7F) << 48 | (long) (emission & 0xF) << 55;
    }
}
