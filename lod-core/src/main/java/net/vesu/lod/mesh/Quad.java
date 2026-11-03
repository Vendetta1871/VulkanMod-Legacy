package net.vesu.lod.mesh;

/**
 * One LOD quad in a {@code long} (AUDIT 5.13, with Vesu's plane rule):
 * <ul>
 *   <li>bits 55..62: light byte ({@code block << 4 | sky});</li>
 *   <li>bits 46..54: biome id, zero unless the model is biome-tinted;</li>
 *   <li>bits 26..45: model id;</li>
 *   <li>bits 21..25 x, 16..20 y, 11..15 z: the minimum voxel of the quad in the section;
 *       the meshing axis keeps the voxel's own coordinate and the vertex stage puts a
 *       positive face on the far side of that voxel;</li>
 *   <li>bits 7..10 height - 1, 3..6 width - 1 (at most 16 x 16);</li>
 *   <li>bits 0..2: face = 1.12 {@code EnumFacing} index (down, up, north, south, west, east).</li>
 * </ul>
 * Width runs along x for Y and Z faces and along z for X faces; height along z for Y faces
 * and along y otherwise.
 */
public final class Quad {
    private Quad() {}

    public static long pack(long upper, int x, int y, int z, int w, int h, int face) {
        return upper | (long) x << 21 | (long) y << 16 | (long) z << 11 | (long) (h - 1) << 7 | (long) (w - 1) << 3 | face;
    }

    /** The light, biome and model fields of a quad. */
    public static long upper(int light, int biome, int model) {
        return (long) (light & 0xFF) << 55 | (long) (biome & 0x1FF) << 46 | (long) (model & 0xFFFFF) << 26;
    }

    public static int face(long q) { return (int) q & 7; }
    public static int width(long q) { return ((int) (q >>> 3) & 15) + 1; }
    public static int height(long q) { return ((int) (q >>> 7) & 15) + 1; }
    public static int x(long q) { return (int) (q >>> 21) & 31; }
    public static int y(long q) { return (int) (q >>> 16) & 31; }
    public static int z(long q) { return (int) (q >>> 11) & 31; }
    public static int model(long q) { return (int) (q >>> 26) & 0xFFFFF; }
    public static int biome(long q) { return (int) (q >>> 46) & 0x1FF; }
    public static int light(long q) { return (int) (q >>> 55) & 0xFF; }
}
