package net.vesu.lod.mesh;

/**
 * A built section (AUDIT 5.13): quads packed in group order (translucent, double-sided,
 * down, up, north, south, west, east) with per-group counts, the AABB in voxels
 * ({@code minX | minY<<5 | minZ<<10 | (maxX-minX-1)<<15 | (maxY-minY-1)<<20 | (maxZ-minZ-1)<<25})
 * and the child mask. An empty result still carries the mask.
 */
public final class MeshResult {
    public static final int GROUPS = 8, G_TRANSLUCENT = 0, G_DOUBLE = 1, G_FACE = 2;

    public final long key;
    public final long[] quads;
    public final int[] counts;
    public final int aabb;
    public final int childMask;

    public MeshResult(long key, long[] quads, int[] counts, int aabb, int childMask) {
        this.key = key;
        this.quads = quads;
        this.counts = counts;
        this.aabb = aabb;
        this.childMask = childMask;
    }

    public boolean empty() {
        return quads.length == 0;
    }

    public int start(int group) {
        int s = 0;
        for (int g = 0; g < group; g++) s += counts[g];
        return s;
    }
}
