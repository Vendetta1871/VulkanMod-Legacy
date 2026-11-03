package net.vesu.lod;

/**
 * One 16^3 chunk section plus its own mip tail (AUDIT 5.3), the ingest input.
 * Level 0: 4096 voxels at 0 ({@code y<<8 | z<<4 | x}); level 1: 8^3 at 4096; level 2: 4^3
 * at 4608; level 3: 2^3 at 4672; level 4: 1 at 4680 (AUDIT 5.3 lists 5120/5128, which do not
 * fit the 4681-long array; the levels are packed back to back).
 */
public final class SectionSnapshot {
    public static final int[] OFFSET = {0, 4096, 4608, 4672, 4680};
    public static final int SIZE = 4681;

    public final long[] data = new long[SIZE];
    /** Chunk-section coordinates (16-block units). */
    public int x, y, z;
    public int nonAir;

    public SectionSnapshot set(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /** Counts level 0 and builds levels 1..4 from it. */
    public void finish(Mip.Opacity op) {
        int n = 0;
        for (int i = 0; i < 4096; i++) if (!Voxel.isAir(data[i])) n++;
        nonAir = n;
        for (int lvl = 1; lvl <= 4; lvl++) {
            Mip.downsample(data, OFFSET[lvl - 1], data, OFFSET[lvl], 16 >> lvl, op);
        }
    }
}
