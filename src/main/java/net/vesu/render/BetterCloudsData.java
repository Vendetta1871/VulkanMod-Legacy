package net.vesu.render;

import java.util.Arrays;
import java.util.Random;

/**
 * Baked data of {@link BetterClouds}, synthesized at startup from fixed seeds: the travel path
 * the cloud field drifts along, the noise texture driving the size wobble and the light
 * gradient that colours the clouds by view/sun angle and time of day.
 */
final class BetterCloudsData {

    private BetterCloudsData() {}

    // --- travel path ---

    private static final int PATH_POINTS = 10784;
    private static final int[] PATH_X = new int[PATH_POINTS];
    private static final int[] PATH_Z = new int[PATH_POINTS];
    /** X offset added per full traversal: 2 * x_last - x_secondToLast. */
    private static final long WRAP_X;

    static {
        // A 20-block-step random walk whose heading (relative to -X) is a mean-reverting AR(1)
        // process (correlation length ~25 steps, stationary sigma ~47 degrees, occasional sharp
        // turns), clamped to +-90 degrees so the path never heads toward +X.
        Random random = new Random(0x5EEDC10D5L);
        double x = 0.0, z = 0.0, heading = 0.0;
        double rho = 0.96;
        double sigma = Math.toRadians(11.5);
        for (int i = 0; i < PATH_POINTS; i++) {
            PATH_X[i] = (int) Math.round(x);
            PATH_Z[i] = (int) Math.round(z);
            double kick = random.nextGaussian() * sigma;
            if (random.nextFloat() < 0.06f) kick *= 3.0;
            heading = heading * rho + kick;
            heading = Math.max(-Math.PI / 2.0, Math.min(Math.PI / 2.0, heading));
            x -= 20.0 * Math.cos(heading);
            z += 20.0 * Math.sin(heading);
        }
        WRAP_X = 2L * PATH_X[PATH_POINTS - 1] - PATH_X[PATH_POINTS - 2];
    }

    private static double pointX(long i) {
        long wraps = Math.floorDiv(i, PATH_POINTS);
        return PATH_X[(int) (i - wraps * PATH_POINTS)] + (double) wraps * WRAP_X;
    }

    private static double pointZ(long i) {
        long wraps = Math.floorDiv(i, PATH_POINTS);
        return PATH_Z[(int) (i - wraps * PATH_POINTS)];
    }

    /** Travel origin (x, z) at path position {@code s} (points), Catmull-Rom between p[i+1] and p[i+2]. */
    static double[] pathAt(double s) {
        long i = (long) Math.floor(s);
        double f = s - i;
        return new double[]{
                catmullRom(pointX(i), pointX(i + 1), pointX(i + 2), pointX(i + 3), f),
                catmullRom(pointZ(i), pointZ(i + 1), pointZ(i + 2), pointZ(i + 3), f)};
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2.0 * p1 + (p2 - p0) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (3.0 * p1 - p0 - 3.0 * p2 + p3) * t3);
    }

    // --- noise texture ---

    /**
     * 128x128 RGBA; only red is read. White noise blurred with a sigma ~0.95 px Gaussian
     * (wrapping), then rank-remapped onto the target distribution: 41..255, skewed bright with
     * a clipped top (5/25/50/75/95% quantiles 68/107/176/238/255, ~28% at or above 232).
     */
    static byte[] noiseTexture() {
        int size = 128;
        Random random = new Random(0x0B5E55EDL);
        float[] white = new float[size * size];
        for (int i = 0; i < white.length; i++) white[i] = random.nextFloat();

        double sigma = 0.95;
        int radius = 3;
        double[] kernel = new double[2 * radius + 1];
        double sum = 0.0;
        for (int k = -radius; k <= radius; k++) {
            kernel[k + radius] = Math.exp(-k * k / (2.0 * sigma * sigma));
            sum += kernel[k + radius];
        }
        for (int k = 0; k < kernel.length; k++) kernel[k] /= sum;

        float[] tmp = new float[white.length], blurred = new float[white.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double acc = 0.0;
                for (int k = -radius; k <= radius; k++) {
                    acc += kernel[k + radius] * white[y * size + Math.floorMod(x + k, size)];
                }
                tmp[y * size + x] = (float) acc;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double acc = 0.0;
                for (int k = -radius; k <= radius; k++) {
                    acc += kernel[k + radius] * tmp[Math.floorMod(y + k, size) * size + x];
                }
                blurred[y * size + x] = (float) acc;
            }
        }

        // Rank remap through a piecewise-linear quantile function.
        Integer[] order = new Integer[blurred.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(blurred[a], blurred[b]));
        double[] qp = {0.0, 0.05, 0.25, 0.50, 0.72, 0.86, 1.0};
        double[] qv = {41, 68, 107, 176, 232, 255, 255};
        byte[] rgba = new byte[size * size * 4];
        for (int rank = 0; rank < order.length; rank++) {
            double p = (rank + 0.5) / order.length;
            int seg = 0;
            while (seg < qp.length - 2 && p > qp[seg + 1]) seg++;
            double t = (p - qp[seg]) / (qp[seg + 1] - qp[seg]);
            int value = (int) Math.round(qv[seg] + (qv[seg + 1] - qv[seg]) * t);
            int at = order[rank] * 4;
            rgba[at] = (byte) Math.min(255, Math.max(0, value));
            rgba[at + 1] = (byte) 255;
            rgba[at + 2] = (byte) 255;
            rgba[at + 3] = (byte) 255;
        }
        return rgba;
    }

    // --- light gradient ---

    /** Sampled columns of the reference table. */
    private static final int[] COLS = {0, 2, 4, 6, 8, 11, 14, 17, 20, 24, 28, 31};

    /** Rows 0..21 at {@link #COLS} (rows 4..12 share one entry, rows 14..16 mirror 2..0). */
    private static final int[][] ROWS = {
            /* 0 */ {0xfdfcfa, 0xebcebe, 0xe4cbc0, 0xdecac1, 0xd7c7c6, 0xd1c6ca, 0xc7c6ce, 0xc7c6ce, 0xbdc5d3, 0xb5bdcc, 0xaeb6c5, 0xaeb6c5},
            /* 1 */ {0xffffff, 0xf0ebea, 0xeeeef1, 0xeeeef1, 0xedf0f6, 0xe6eaf1, 0xe2e7ee, 0xdce3ee, 0xdce3ee, 0xd1dae9, 0xc3cfe2, 0xc3cfe2},
            /* 2 */ {0xffffff, 0xf5f7fa, 0xf5f7fa, 0xf1f4f8, 0xf1f4f8, 0xedf0f6, 0xe7ebf2, 0xe2e7ee, 0xe2e7ee, 0xdce3ee, 0xdce3ee, 0xc3cfe2},
            /* 3 */ {0xffffff, 0xffffff, 0xffffff, 0xfafbfd, 0xf5f7fa, 0xf0f3f7, 0xedf0f6, 0xedf0f6, 0xebeff5, 0xebeff5, 0xebeff5, 0xe2e7ee},
            /* 4..12 */ {0xffffff, 0xffffff, 0xffffff, 0xfcfdfe, 0xfafbfd, 0xf5f7fa, 0xedf0f6, 0xedf0f6, 0xedf0f6, 0xedf0f6, 0xedf0f6, 0xedf0f6},
            /* 13 */ {0xffffff, 0xffffff, 0xffffff, 0xfafbfd, 0xf5f7fa, 0xf0f3f7, 0xecf0f6, 0xecf0f6, 0xecf0f6, 0xebeff5, 0xebeff5, 0xe2e7ee},
            /* 17 */ {0xfeff81, 0xdd7044, 0xbd6448, 0x9e584c, 0x8d524e, 0x654a56, 0x594858, 0x4d465a, 0x36415f, 0x36415f, 0x36415f, 0x434e69},
            /* 18 */ {0xd9653e, 0x884c46, 0x583436, 0x4d2e31, 0x3b2328, 0x261d27, 0x1d1f2d, 0x1d1f2d, 0x1d1f2d, 0x212435, 0x2b3248, 0x434e69},
            /* 19 */ {0x633a3a, 0x3f2529, 0x2f1c22, 0x281a22, 0x231922, 0x211b26, 0x1b1721, 0x15141c, 0x181924, 0x1d1f2d, 0x262b3e, 0x657595},
            /* 20 */ {0x2f1c22, 0x231922, 0x1c161f, 0x15141c, 0x15141c, 0x15141c, 0x15141c, 0x15141c, 0x15141c, 0x1d1f2d, 0x262b3e, 0x657595},
            /* 21 */ {0x231922, 0x15141c, 0x15141c, 0x15141c, 0x15141c, 0x15141c, 0x15141c, 0x15141c, 0x15141c, 0x181924, 0x212435, 0x9aaed3},
    };

    /** Night rows 22..26: dark, with the moonlit ramp in the last columns. */
    private static final int[] NIGHT_COLS = {0, 24, 27, 28, 29, 30, 31};
    private static final int[] NIGHT = {0x15141c, 0x15141c, 0x181924, 0x1d1f2d, 0x212435, 0x586580, 0xa4b6d7};

    /**
     * 32x32 RGBA. U = scattering angle (column 0 toward the sun, 31 away from it), V = time of
     * day (row ~ mappedTime / 24000 * 32 - 0.5: row 0 sunrise, 8 noon, 16 sunset, 24 midnight).
     * Rows 27..31 mirror rows 21..17 (row r ~ row (16 - r) mod 32).
     */
    static byte[] lightGradient() {
        int[][] table = new int[32][];
        table[0] = expand(COLS, ROWS[0]);
        table[1] = expand(COLS, ROWS[1]);
        table[2] = expand(COLS, ROWS[2]);
        table[3] = expand(COLS, ROWS[3]);
        for (int r = 4; r <= 12; r++) table[r] = expand(COLS, ROWS[4]);
        table[13] = expand(COLS, ROWS[5]);
        table[14] = table[2];
        table[15] = table[1];
        table[16] = table[0];
        for (int r = 17; r <= 21; r++) table[r] = expand(COLS, ROWS[r - 11]);
        for (int r = 22; r <= 26; r++) table[r] = expand(NIGHT_COLS, NIGHT);
        for (int r = 27; r <= 31; r++) table[r] = table[Math.floorMod(16 - r, 32)];

        byte[] rgba = new byte[32 * 32 * 4];
        for (int r = 0; r < 32; r++) {
            for (int c = 0; c < 32; c++) {
                int rgb = table[r][c];
                int at = (r * 32 + c) * 4;
                rgba[at] = (byte) (rgb >> 16);
                rgba[at + 1] = (byte) (rgb >> 8);
                rgba[at + 2] = (byte) rgb;
                rgba[at + 3] = (byte) 255;
            }
        }
        return rgba;
    }

    /** Linear interpolation (per channel) between the anchor columns. */
    private static int[] expand(int[] cols, int[] colors) {
        int[] row = new int[32];
        int seg = 0;
        for (int c = 0; c < 32; c++) {
            while (seg < cols.length - 2 && c > cols[seg + 1]) seg++;
            float t = (c - cols[seg]) / (float) (cols[seg + 1] - cols[seg]);
            t = Math.max(0.0f, Math.min(1.0f, t));
            int a = colors[seg], b = colors[seg + 1];
            int rgb = 0;
            for (int shift = 16; shift >= 0; shift -= 8) {
                int ca = (a >> shift) & 0xFF, cb = (b >> shift) & 0xFF;
                rgb |= Math.round(ca + (cb - ca) * t) << shift;
            }
            row[c] = rgb;
        }
        return row;
    }
}
