package net.vesu.lod;

/**
 * The 8 -> 1 mip rule (AUDIT 5.3). Children are indexed {@code x | y<<1 | z<<2}.
 * The non-air child with the highest light opacity wins, ties going to the higher index
 * ({@code opacity<<4 | index}); its whole voxel is the result. Eight air children give air
 * with averaged light: block = floor(sum / 8), sky = ceil(sum / 8).
 */
public final class Mip {
    /** Light opacity 0..15 of a LOD state id. */
    @FunctionalInterface
    public interface Opacity {
        int of(int stateId);
    }

    private Mip() {}

    /**
     * Mips the 2^3 cube at {@code src[b]} whose rows are {@code s} apart and layers {@code ss}
     * apart (x fastest, then z, then y).
     */
    public static long mip(long[] src, int b, int s, int ss, Opacity op) {
        int best = -1;
        long bestV = 0;
        int block = 0, sky = 0;
        for (int i = 0; i < 8; i++) {
            long v = src[b + (i & 1) + ((i >> 1) & 1) * ss + (i >> 2) * s];
            if (Voxel.isAir(v)) {
                block += Voxel.blockLight(v);
                sky += Voxel.skyLight(v);
                continue;
            }
            int score = op.of(Voxel.state(v)) << 4 | i;
            if (score > best) {
                best = score;
                bestV = v;
            }
        }
        if (best >= 0) return bestV;
        return Voxel.of(0, 0, (block >> 3) << 4 | ((sky + 7) >> 3));
    }

    /**
     * Mips one cube of side {@code 2n} in {@code src} (index {@code y*s*s + z*s + x} with
     * {@code s = 2n}, starting at {@code srcOff}) into side {@code n} at {@code dstOff}.
     */
    public static void downsample(long[] src, int srcOff, long[] dst, int dstOff, int n, Opacity op) {
        int s = n * 2, ss = s * s;
        for (int y = 0; y < n; y++) {
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    dst[dstOff + (y * n + z) * n + x] = mip(src, srcOff + (2 * y) * ss + (2 * z) * s + 2 * x, s, ss, op);
                }
            }
        }
    }
}
