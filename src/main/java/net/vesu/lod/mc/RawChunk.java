package net.vesu.lod.mc;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.vesu.lod.LodWorld;
import net.vesu.lod.SectionSnapshot;
import net.vesu.lod.Voxel;

/**
 * A copy of a client chunk's arrays (AUDIT 5.6): taken on the client thread, converted to
 * voxels and inserted on a worker. Only the sections in {@link #present} are inserted; a
 * present section without storage is air.
 */
public final class RawChunk {
    public final int cx, cz;
    final boolean sky;
    final byte[] biomes;
    final byte[][] blocks = new byte[16][], add = new byte[16][], meta = new byte[16][],
            blockLight = new byte[16][], skyLight = new byte[16][];
    /** Imported sections: runtime block ids already remapped from the save's (AUDIT 5.8). */
    final short[][] ids = new short[16][];
    int present;

    private RawChunk(int cx, int cz, boolean sky, byte[] biomes) {
        this.cx = cx;
        this.cz = cz;
        this.sky = sky;
        this.biomes = biomes;
    }

    /** Copies the sections in {@code mask} (bit y) of a loaded chunk. Client thread. */
    public static RawChunk grab(Chunk c, boolean sky, int mask) {
        RawChunk r = new RawChunk(c.x, c.z, sky, c.getBiomeArray().clone());
        ExtendedBlockStorage[] storage = c.getBlockStorageArray();
        for (int y = 0; y < 16; y++) {
            if ((mask & 1 << y) == 0) continue;
            r.present |= 1 << y;
            ExtendedBlockStorage s = storage[y];
            if (s == null || s == Chunk.NULL_BLOCK_STORAGE) continue;
            byte[] ids = new byte[4096];
            NibbleArray data = new NibbleArray();
            NibbleArray extra = s.getData().getDataForNBT(ids, data);
            r.blocks[y] = ids;
            r.meta[y] = data.getData();
            r.add[y] = extra == null ? null : extra.getData();
            r.blockLight[y] = s.getBlockLight().getData().clone();
            NibbleArray skyArr = s.getSkyLight();
            r.skyLight[y] = skyArr == null ? null : skyArr.getData().clone();
        }
        return r;
    }

    /** An empty chunk to fill from a save; every section present (air until set). */
    static RawChunk forImport(int cx, int cz, boolean sky, byte[] biomes) {
        RawChunk r = new RawChunk(cx, cz, sky, biomes);
        r.present = 0xFFFF;
        return r;
    }

    void setImported(int y, short[] runtimeIds, byte[] meta, byte[] blockLight, byte[] skyLight) {
        ids[y] = runtimeIds;
        this.meta[y] = meta;
        this.blockLight[y] = blockLight;
        this.skyLight[y] = skyLight;
    }

    private static int nibble(byte[] a, int i) {
        return (a[i >> 1] >> ((i & 1) << 2)) & 15;
    }

    /** Converts and inserts every present section. Worker thread. */
    void insertInto(LodWorld world, StateMapper mapper, SectionSnapshot snap) {
        int[] biomeIds = new int[256];
        for (int i = 0; i < 256; i++) biomeIds[i] = mapper.lodBiome(biomes == null || biomes.length < 256 ? 1 : biomes[i] & 255);
        int defaultSky = sky ? 15 : 0;
        for (int y = 0; y < 16; y++) {
            if ((present & 1 << y) == 0) continue;
            long[] d = snap.set(cx, y, cz).data;
            byte[] ids = blocks[y];
            short[] full = this.ids[y];
            if (full != null) {
                byte[] m = meta[y], bl = blockLight[y], sl = skyLight[y];
                for (int i = 0; i < 4096; i++) {
                    int st = mapper.lodState((full[i] & 0xFFF) << 4 | (m == null ? 0 : nibble(m, i)));
                    int light = (bl == null ? 0 : nibble(bl, i) << 4) | (sl == null ? defaultSky : nibble(sl, i));
                    d[i] = Voxel.of(st, st == 0 ? 0 : biomeIds[i & 255], light);
                }
            } else if (ids == null) {
                for (int i = 0; i < 4096; i++) d[i] = Voxel.of(0, 0, defaultSky);
            } else {
                byte[] m = meta[y], a = add[y], bl = blockLight[y], sl = skyLight[y];
                for (int i = 0; i < 4096; i++) {
                    int id = (ids[i] & 255) | (a == null ? 0 : nibble(a, i) << 8);
                    int st = mapper.lodState(id << 4 | nibble(m, i));
                    int light = nibble(bl, i) << 4 | (sl == null ? 0 : nibble(sl, i));
                    d[i] = Voxel.of(st, st == 0 ? 0 : biomeIds[i & 255], light);
                }
            }
            snap.finish(world.registry);
            world.insert(snap);
        }
    }
}
