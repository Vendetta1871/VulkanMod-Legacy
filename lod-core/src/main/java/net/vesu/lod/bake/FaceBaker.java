package net.vesu.lod.bake;

import java.util.List;

/**
 * Software rasterizer of the bakery (AUDIT 5.14): draws a block model's quads into six 16x16
 * face images, one per side, with an orthographic view along that side's normal.
 *
 * <p>Face images map world position, not view orientation: Y faces use (u, v) = (x, z),
 * Z faces (x, y), X faces (z, y), each in block units [0, 1). Depth runs inward from the
 * face's own plane. The LOD vertex stage uses the same mapping, so a face shows exactly
 * what lies on it.</p>
 *
 * <p>Pixel: bits 0..31 ABGR color; 32..38 coverage (1 when written); 39 tinted;
 * 40..63 depth (24 bits, all ones = clear).</p>
 */
public final class FaceBaker {
    public static final int RES = 16, PIXELS = RES * RES;
    public static final long CLEAR = 0xFFFFFFL << 40;

    /** One input quad: four corners in block space with atlas uv, counter-clockwise. */
    public static final class Quad {
        public final float[] x = new float[4], y = new float[4], z = new float[4], u = new float[4], v = new float[4];
        public boolean tinted;
        /** Drawn in the second, blended pass (the TRANSLUCENT layer). */
        public boolean translucent;
        /** Alpha-tested layer: texels with alpha 0 are dropped. */
        public boolean cutout;
        /** Constant vertex color multiplier (ARGB), usually white. */
        public int color = 0xFFFFFFFF;
    }

    /** Nearest atlas texel as ARGB. */
    @FunctionalInterface
    public interface Texels {
        int argb(float u, float v);
    }

    private FaceBaker() {}

    /** Image coordinates and depth of a block-space point for face f. */
    private static void project(int f, float x, float y, float z, float[] out) {
        switch (f) {
            case 0 -> { out[0] = x; out[1] = z; out[2] = y; }
            case 1 -> { out[0] = x; out[1] = z; out[2] = 1 - y; }
            case 2 -> { out[0] = x; out[1] = y; out[2] = z; }
            case 3 -> { out[0] = x; out[1] = y; out[2] = 1 - z; }
            case 4 -> { out[0] = z; out[1] = y; out[2] = x; }
            default -> { out[0] = z; out[1] = y; out[2] = 1 - x; }
        }
    }

    private static final float[][] NORMALS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    /** Bakes all six faces; {@code images[f][py * 16 + px]}. */
    public static long[][] bake(List<Quad> quads, Texels texels) {
        long[][] images = new long[6][PIXELS];
        for (int f = 0; f < 6; f++) {
            java.util.Arrays.fill(images[f], CLEAR);
            for (int pass = 0; pass < 2; pass++) {
                for (Quad q : quads) {
                    if (q.translucent != (pass == 1)) continue;
                    triangle(images[f], f, q, 0, 1, 2, texels);
                    triangle(images[f], f, q, 0, 2, 3, texels);
                }
            }
        }
        return images;
    }

    private static void triangle(long[] img, int f, Quad q, int a, int b, int c, Texels texels) {
        float ax = q.x[b] - q.x[a], ay = q.y[b] - q.y[a], az = q.z[b] - q.z[a];
        float bx = q.x[c] - q.x[a], by = q.y[c] - q.y[a], bz = q.z[c] - q.z[a];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float[] n = NORMALS[f];
        // Only triangles facing the viewer of this side; edge-on ones vanish.
        if (nx * n[0] + ny * n[1] + nz * n[2] <= 1e-6f) return;
        float[] p0 = new float[3], p1 = new float[3], p2 = new float[3];
        project(f, q.x[a], q.y[a], q.z[a], p0);
        project(f, q.x[b], q.y[b], q.z[b], p1);
        project(f, q.x[c], q.y[c], q.z[c], p2);
        float area = (p1[0] - p0[0]) * (p2[1] - p0[1]) - (p1[1] - p0[1]) * (p2[0] - p0[0]);
        if (Math.abs(area) < 1e-9f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(p0[0], Math.min(p1[0], p2[0])) * RES));
        int maxX = Math.min(RES - 1, (int) Math.ceil(Math.max(p0[0], Math.max(p1[0], p2[0])) * RES));
        int minY = Math.max(0, (int) Math.floor(Math.min(p0[1], Math.min(p1[1], p2[1])) * RES));
        int maxY = Math.min(RES - 1, (int) Math.ceil(Math.max(p0[1], Math.max(p1[1], p2[1])) * RES));
        for (int py = minY; py <= maxY; py++) {
            float sy = (py + 0.5f) / RES;
            for (int px = minX; px <= maxX; px++) {
                float sx = (px + 0.5f) / RES;
                float w0 = ((p1[0] - sx) * (p2[1] - sy) - (p1[1] - sy) * (p2[0] - sx)) / area;
                float w1 = ((p2[0] - sx) * (p0[1] - sy) - (p2[1] - sy) * (p0[0] - sx)) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < -1e-5f || w1 < -1e-5f || w2 < -1e-5f) continue;
                float depth = w0 * p0[2] + w1 * p1[2] + w2 * p2[2];
                long d = Math.max(0, Math.min(0xFFFFFE, (long) (Math.max(0f, depth) * 0xFFFFFE)));
                int i = py * RES + px;
                long old = img[i];
                if (q.translucent) {
                    // Blended over whatever is there, but never behind an opaque pixel nearer the viewer.
                    if (old != CLEAR && (old >>> 40) < d && ((int) old >>> 24) == 0xFF) continue;
                } else if ((old >>> 40) <= d) {
                    continue;
                }
                float u = w0 * q.u[a] + w1 * q.u[b] + w2 * q.u[c];
                float v = w0 * q.v[a] + w1 * q.v[b] + w2 * q.v[c];
                int argb = multiply(texels.argb(u, v), q.color);
                int alpha = argb >>> 24;
                if (alpha == 0 || (q.cutout && alpha < 26)) continue;
                long px32;
                if (q.translucent && old != CLEAR) {
                    int dst = abgrToArgb((int) old);
                    argb = blend(argb, dst);
                    px32 = argbToAbgr(argb) & 0xFFFFFFFFL;
                    d = Math.min(d, old >>> 40);
                } else {
                    if (!q.translucent && !q.cutout) argb |= 0xFF000000;
                    px32 = argbToAbgr(argb) & 0xFFFFFFFFL;
                }
                boolean tinted = q.tinted || (q.translucent && old != CLEAR && (old >>> 39 & 1) != 0);
                img[i] = px32 | 1L << 32 | (tinted ? 1L << 39 : 0) | d << 40;
            }
        }
    }

    static int multiply(int a, int b) {
        int r = ((a >> 16 & 255) * (b >> 16 & 255) + 127) / 255;
        int g = ((a >> 8 & 255) * (b >> 8 & 255) + 127) / 255;
        int bl = ((a & 255) * (b & 255) + 127) / 255;
        int al = ((a >>> 24) * (b >>> 24) + 127) / 255;
        return al << 24 | r << 16 | g << 8 | bl;
    }

    /** {@code a = min(255, srcA + dstA (255 - srcA) / 256)}, colors mixed by alpha. */
    static int blend(int src, int dst) {
        int sa = src >>> 24, da = dst >>> 24;
        int a = Math.min(255, sa + da * (255 - sa) / 256);
        int r = ((src >> 16 & 255) * sa + (dst >> 16 & 255) * (255 - sa)) / 255;
        int g = ((src >> 8 & 255) * sa + (dst >> 8 & 255) * (255 - sa)) / 255;
        int b = ((src & 255) * sa + (dst & 255) * (255 - sa)) / 255;
        return a << 24 | r << 16 | g << 8 | b;
    }

    public static int argbToAbgr(int c) {
        return (c & 0xFF00FF00) | (c >> 16 & 0xFF) | (c & 0xFF) << 16;
    }

    public static int abgrToArgb(int c) {
        return argbToAbgr(c);
    }
}
