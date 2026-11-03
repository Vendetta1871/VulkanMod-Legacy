package net.vesu.lod.mesh;

import net.vesu.lod.Voxel;
import net.vesu.lod.WorldSection;

import java.util.Arrays;

/**
 * Builds the quads of one 32^3 section (AUDIT 5.13). One mesher per thread; all state is
 * reset per build. Faces are culled against the neighbour voxel (inside the section or from
 * the neighbour layers), lit from the side they face, and merged greedily per plane up to
 * 16 x 16 while light, biome and model match.
 */
public final class Mesher {
    /** Thrown internally when a state has no baked model yet. */
    public static final class BakeMiss extends RuntimeException {
        public final int stateId;

        BakeMiss(int stateId) {
            super(null, null, false, false);
            this.stateId = stateId;
        }
    }

    private static final int N = 32, NN = N * N;
    private final int[] model = new int[WorldSection.SIZE];
    private final long[] meta = new long[WorldSection.SIZE];
    private final long[] mask = new long[NN];
    private final int[] groupOf = new int[NN];
    private long[][] out = new long[MeshResult.GROUPS][];
    private final int[] counts = new int[MeshResult.GROUPS];
    private int minX, minY, minZ, maxX, maxY, maxZ;
    // Neighbour layer scratch, per face: the 32x32 voxels across that face, (u, v) as the face maps them.
    private final long[][] nbr = new long[6][NN];
    private final int[][] nbrModel = new int[6][NN];
    private final long[][] nbrMeta = new long[6][NN];

    public Mesher() {
        for (int g = 0; g < MeshResult.GROUPS; g++) out[g] = new long[1024];
    }

    /** Index of voxel (u, v) of plane p for face axis a (0 Y, 1 Z, 2 X). */
    private static int index(int axis, int p, int u, int v) {
        return switch (axis) {
            case 0 -> WorldSection.index(u, p, v);   // u = x, v = z
            case 1 -> WorldSection.index(u, v, p);   // u = x, v = y
            default -> WorldSection.index(p, v, u);  // u = z, v = y
        };
    }

    /**
     * Copies the layer of {@code neighbor} that touches face {@code face} of this section
     * (null: air under the open sky, e.g. above the world).
     */
    public void setNeighbor(int face, long[] neighbor, ModelSource models) {
        long[] layer = nbr[face];
        int[] lm = nbrModel[face];
        long[] lmeta = nbrMeta[face];
        if (neighbor == null) {
            Arrays.fill(layer, Voxel.AIR_SKYLIT);
            Arrays.fill(lm, ModelSource.NONE);
            return;
        }
        int axis = face >> 1;
        int p = (face & 1) == 1 ? 0 : N - 1;   // the near layer of the neighbour
        for (int v = 0; v < N; v++) {
            for (int u = 0; u < N; u++) {
                long val = neighbor[index(axis, p, u, v)];
                int i = v * N + u;
                layer[i] = val;
                if (Voxel.isAir(val)) {
                    lm[i] = ModelSource.NONE;
                } else {
                    int m = models.model(Voxel.state(val));
                    if (m == ModelSource.UNBAKED) throw new BakeMiss(Voxel.state(val));
                    lm[i] = m;
                    if (m >= 0) lmeta[i] = models.meta(m);
                }
            }
        }
    }

    private void emit(int group, long q, int x, int y, int z, int w, int h, int face) {
        long[] g = out[group];
        if (counts[group] == g.length) out[group] = g = Arrays.copyOf(g, g.length * 2);
        g[counts[group]++] = q;
        int axis = face >> 1;
        int x1, y1, z1;
        switch (axis) {
            case 0 -> { x1 = x + w; y1 = y + 1; z1 = z + h; }
            case 1 -> { x1 = x + w; y1 = y + h; z1 = z + 1; }
            default -> { x1 = x + 1; y1 = y + h; z1 = z + w; }
        }
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x1);
        maxY = Math.max(maxY, y1);
        maxZ = Math.max(maxZ, z1);
    }

    /**
     * Builds the section. Neighbour layers must be set first for all six faces.
     *
     * @throws BakeMiss when a state has no baked model; the caller bakes and retries
     */
    public MeshResult build(long key, long[] data, int childMask, ModelSource models) {
        Arrays.fill(counts, 0);
        minX = minY = minZ = N;
        maxX = maxY = maxZ = 0;
        boolean any = false;
        for (int i = 0; i < WorldSection.SIZE; i++) {
            long v = data[i];
            if (Voxel.isAir(v)) {
                model[i] = ModelSource.NONE;
                continue;
            }
            int m = models.model(Voxel.state(v));
            if (m == ModelSource.UNBAKED) throw new BakeMiss(Voxel.state(v));
            model[i] = m;
            if (m >= 0) {
                meta[i] = models.meta(m);
                any = true;
            }
        }
        if (any) {
            for (int face = 0; face < 6; face++) meshFace(data, face);
        }
        int total = 0;
        for (int c : counts) total += c;
        long[] quads = new long[total];
        int o = 0;
        for (int g = 0; g < MeshResult.GROUPS; g++) {
            System.arraycopy(out[g], 0, quads, o, counts[g]);
            o += counts[g];
        }
        int aabb = 0;
        if (total > 0) {
            aabb = minX | minY << 5 | minZ << 10 | Math.max(0, maxX - minX - 1) << 15
                    | Math.max(0, maxY - minY - 1) << 20 | Math.max(0, maxZ - minZ - 1) << 25;
        }
        return new MeshResult(key, quads, counts.clone(), aabb, childMask);
    }

    private void meshFace(long[] data, int face) {
        int axis = face >> 1;
        boolean positive = (face & 1) == 1;
        int opposite = face ^ 1;
        long[] layer = nbr[face];
        int[] lmodel = nbrModel[face];
        long[] lmeta = nbrMeta[face];
        for (int p = 0; p < N; p++) {
            boolean anyFace = false;
            int np = positive ? p + 1 : p - 1;
            boolean inside = np >= 0 && np < N;
            for (int v = 0; v < N; v++) {
                for (int u = 0; u < N; u++) {
                    int c = v * N + u;
                    mask[c] = 0;
                    int i = index(axis, p, u, v);
                    int m = model[i];
                    if (m < 0) continue;
                    long mm = meta[i];
                    int fb = ModelMeta.face(mm, face);
                    if (fb == ModelMeta.NO_FACE) continue;
                    long nv;
                    int nm;
                    long nmeta = 0;
                    if (inside) {
                        int j = index(axis, np, u, v);
                        nv = data[j];
                        nm = model[j];
                        if (nm >= 0) nmeta = meta[j];
                    } else {
                        nv = layer[c];
                        nm = lmodel[c];
                        if (nm >= 0) nmeta = lmeta[c];
                    }
                    if (nm >= 0) {
                        if (ModelMeta.has(nmeta, ModelMeta.OPAQUE)) continue;
                        int nfb = ModelMeta.face(nmeta, opposite);
                        if (nfb != ModelMeta.NO_FACE && (nfb & ModelMeta.F_OCCLUDES) != 0
                                && (fb & ModelMeta.F_OCCLUDABLE) != 0) continue;
                        if (nm == m && ModelMeta.has(mm, ModelMeta.CULLS_OWN)) continue;
                    }
                    long own = data[i];
                    int light;
                    if ((fb & ModelMeta.F_SELF_LIT) != 0) {
                        light = Voxel.light(own);
                    } else if (ModelMeta.has(mm, ModelMeta.OPAQUE)) {
                        light = Voxel.light(nv);
                    } else {
                        // Non-opaque: the brighter of both sides per channel (a slab lit from above).
                        int a = Voxel.light(own), b = Voxel.light(nv);
                        light = Math.max(a & 0xF0, b & 0xF0) | Math.max(a & 15, b & 15);
                    }
                    int biome = ModelMeta.has(mm, ModelMeta.TINTED) ? Voxel.biome(own) : 0;
                    mask[c] = Quad.upper(light, biome, m) | 1;
                    groupOf[c] = ModelMeta.has(mm, ModelMeta.TRANSLUCENT) ? MeshResult.G_TRANSLUCENT
                            : ModelMeta.has(mm, ModelMeta.DOUBLE_SIDED) ? MeshResult.G_DOUBLE : MeshResult.G_FACE + face;
                    anyFace = true;
                }
            }
            if (anyFace) greedy(axis, p, face);
        }
    }

    private void greedy(int axis, int p, int face) {
        for (int v = 0; v < N; v++) {
            for (int u = 0; u < N; ) {
                long k = mask[v * N + u];
                if (k == 0) {
                    u++;
                    continue;
                }
                int w = 1;
                while (u + w < N && w < 16 && mask[v * N + u + w] == k) w++;
                int h = 1;
                outer:
                while (v + h < N && h < 16) {
                    int row = (v + h) * N + u;
                    for (int d = 0; d < w; d++) if (mask[row + d] != k) break outer;
                    h++;
                }
                for (int dv = 0; dv < h; dv++) Arrays.fill(mask, (v + dv) * N + u, (v + dv) * N + u + w, 0L);
                int x, y, z;
                switch (axis) {
                    case 0 -> { x = u; y = p; z = v; }
                    case 1 -> { x = u; y = v; z = p; }
                    default -> { x = p; y = v; z = u; }
                }
                long upper = k & ~1L;
                emit(groupOf[v * N + u], Quad.pack(upper, x, y, z, w, h, face), x, y, z, w, h, face);
                u += w;
            }
        }
    }
}
