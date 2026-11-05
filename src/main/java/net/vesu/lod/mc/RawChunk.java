package net.vesu.lod.mc;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.vesu.lod.LodWorld;
import net.vesu.lod.Mip;
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
    /** The generation producer that wants this column marked done once it is stored. */
    LodGenerator doneBy;

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

    /** Per worker: the column's states, light and the spread queue. */
    private static final ThreadLocal<Column> COLUMN = ThreadLocal.withInitial(Column::new);

    private static final class Column {
        final int[] states = new int[65536];
        final byte[] block = new byte[65536], sky = new byte[65536], opacity = new byte[65536];
        final int[] queue = new int[65536];
        final boolean[] queued = new boolean[65536];
    }

    /** Converts and inserts every present section. Worker thread. */
    void insertInto(LodWorld world, StateMapper mapper, SectionSnapshot snap) {
        int[] biomeIds = new int[256];
        for (int i = 0; i < 256; i++) biomeIds[i] = mapper.lodBiome(biomes == null || biomes.length < 256 ? 1 : biomes[i] & 255);
        int defaultSky = sky ? 15 : 0;
        Column col = COLUMN.get();
        int[] states = col.states;
        byte[] blockL = col.block, skyL = col.sky;
        // Column index Y << 8 | z << 4 | x, the section index i plus y << 12.
        for (int y = 0; y < 16; y++) {
            if ((present & 1 << y) == 0) continue;
            int base = y << 12;
            byte[] ids = blocks[y];
            short[] full = this.ids[y];
            if (full != null) {
                byte[] m = meta[y], bl = blockLight[y], sl = skyLight[y];
                for (int i = 0; i < 4096; i++) {
                    states[base + i] = mapper.lodState((full[i] & 0xFFF) << 4 | (m == null ? 0 : nibble(m, i)));
                    blockL[base + i] = (byte) (bl == null ? 0 : nibble(bl, i));
                    skyL[base + i] = (byte) (sl == null ? defaultSky : nibble(sl, i));
                }
            } else if (ids == null) {
                java.util.Arrays.fill(states, base, base + 4096, 0);
                java.util.Arrays.fill(blockL, base, base + 4096, (byte) 0);
                java.util.Arrays.fill(skyL, base, base + 4096, (byte) defaultSky);
            } else {
                byte[] m = meta[y], a = add[y], bl = blockLight[y], sl = skyLight[y];
                for (int i = 0; i < 4096; i++) {
                    int id = (ids[i] & 255) | (a == null ? 0 : nibble(a, i) << 8);
                    states[base + i] = mapper.lodState(id << 4 | nibble(m, i));
                    blockL[base + i] = (byte) nibble(bl, i);
                    skyL[base + i] = (byte) (sl == null ? 0 : nibble(sl, i));
                }
            }
        }
        if (sky && present == 0xFFFF) spreadSkyLight(col, world.registry);
        for (int y = 0; y < 16; y++) {
            if ((present & 1 << y) == 0) continue;
            long[] d = snap.set(cx, y, cz).data;
            int base = y << 12;
            for (int i = 0; i < 4096; i++) {
                int st = states[base + i];
                d[i] = Voxel.of(st, st == 0 ? 0 : biomeIds[i & 255], blockL[base + i] << 4 | skyL[base + i]);
            }
            snap.finish(world.registry);
            world.insert(snap);
        }
    }

    /**
     * Raises the column's sky light to what spreading it inside the column gives. Chunks
     * saved or sent before the game spread sky light sideways (fresh terrain at the edge of
     * what was generated) hold it only in the open columns: overhangs and cave mouths stay
     * black in the LODs while vanilla, relit once loaded, shows them lit. Light is only ever
     * raised, so a fully lit column is unchanged; light from the neighbour columns is not
     * reached.
     */
    private static void spreadSkyLight(Column col, Mip.Opacity op) {
        int[] states = col.states, queue = col.queue;
        byte[] sky = col.sky, opacity = col.opacity;
        boolean[] queued = col.queued;
        for (int c = 0; c < 65536; c++) opacity[c] = (byte) Math.min(15, op.of(states[c]));
        // Straight down from the top, as the game does: opacity-0 blocks pass full light.
        for (int xz = 0; xz < 256; xz++) {
            int l = 15;
            for (int y = 255; y >= 0 && l > 0; y--) {
                int c = y << 8 | xz;
                l = Math.max(0, l - opacity[c]);
                if (l > sky[c]) sky[c] = (byte) l;
            }
        }
        int head = 0, size = 0;
        for (int c = 0; c < 65536; c++) {
            queued[c] = sky[c] > 1 && opacity[c] < 15;
            if (queued[c]) queue[size++] = c;
        }
        while (size > 0) {
            int c = queue[head];
            head = (head + 1) & 65535;
            size--;
            queued[c] = false;
            int l = sky[c];
            int x = c & 15, z = c >> 4 & 15, y = c >> 8;
            for (int k = 0; k < 6; k++) {
                int n;
                switch (k) {
                    case 0: if (x == 0) continue; n = c - 1; break;
                    case 1: if (x == 15) continue; n = c + 1; break;
                    case 2: if (z == 0) continue; n = c - 16; break;
                    case 3: if (z == 15) continue; n = c + 16; break;
                    case 4: if (y == 0) continue; n = c - 256; break;
                    default: if (y == 255) continue; n = c + 256; break;
                }
                int o = opacity[n];
                if (o >= 15) continue;
                int nl = l - Math.max(1, o);
                if (nl <= sky[n]) continue;
                sky[n] = (byte) nl;
                if (nl > 1 && !queued[n]) {
                    queued[n] = true;
                    queue[(head + size) & 65535] = n;
                    size++;
                }
            }
        }
    }
}
