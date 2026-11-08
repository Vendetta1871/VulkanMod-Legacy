package net.vesu.lod.mc;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.vesu.lod.LodWorld;
import net.vesu.lod.Mip;
import net.vesu.lod.SectionSnapshot;
import net.vesu.lod.Voxel;

import java.util.IdentityHashMap;

/**
 * A copy of a client chunk's arrays (AUDIT 5.6): taken on the client thread, converted to
 * voxels and inserted on a worker. Only the sections in {@link #present} are inserted; a
 * present section without storage is air.
 *
 * <p>Blocks are kept as runtime state ids ({@code blockId << 4 | meta}) read state by state,
 * and biomes as runtime biome ids read through {@link Chunk#getBiome}. The packed NBT helpers
 * cannot be used: id extenders (RoughlyEnoughIDs, JustEnoughIDs) turn
 * {@code BlockStateContainer.getDataForNBT} into palette indices and leave
 * {@code getBiomeArray} a placeholder, which filled the LODs with wrong blocks and one biome.</p>
 */
public final class RawChunk {
    public final int cx, cz;
    final boolean sky;
    /** Runtime biome id per column ({@code z << 4 | x}), or null (plains). */
    final int[] biomes;
    /** Runtime state id per block ({@code y << 8 | z << 4 | x}); null = air. */
    final int[][] states = new int[16][];
    final byte[][] blockLight = new byte[16][], skyLight = new byte[16][];
    int present;
    /** The generation producer that wants this column marked done once it is stored. */
    LodGenerator doneBy;

    private RawChunk(int cx, int cz, boolean sky, int[] biomes) {
        this.cx = cx;
        this.cz = cz;
        this.sky = sky;
        this.biomes = biomes;
    }

    /** Copies the sections in {@code mask} (bit y) of a loaded chunk. Client thread (or the generator's server thread). */
    public static RawChunk grab(Chunk c, boolean sky, int mask) {
        RawChunk r = new RawChunk(c.x, c.z, sky, biomesOf(c));
        ExtendedBlockStorage[] storage = c.getBlockStorageArray();
        IdentityHashMap<IBlockState, Integer> ids = new IdentityHashMap<>();
        for (int y = 0; y < 16; y++) {
            if ((mask & 1 << y) == 0) continue;
            r.present |= 1 << y;
            ExtendedBlockStorage s = storage[y];
            if (s == null || s == Chunk.NULL_BLOCK_STORAGE) continue;
            int[] st = new int[4096];
            IBlockState last = null;
            int lastId = 0;
            for (int i = 0; i < 4096; i++) {
                IBlockState state = s.get(i & 15, i >> 8, i >> 4 & 15);
                if (state != last) {
                    last = state;
                    Integer id = ids.get(state);
                    if (id == null) {
                        id = stateId(state);
                        ids.put(state, id);
                    }
                    lastId = id;
                }
                st[i] = lastId;
            }
            r.states[y] = st;
            r.blockLight[y] = s.getBlockLight().getData().clone();
            NibbleArray skyArr = s.getSkyLight();
            r.skyLight[y] = skyArr == null ? null : skyArr.getData().clone();
        }
        return r;
    }

    /** The storage id of a state, {@code blockId << 4 | meta}, without the 4096-block limit. */
    @SuppressWarnings("deprecation")
    private static int stateId(IBlockState state) {
        int id = Block.BLOCK_STATE_IDS.get(state);
        if (id >= 0) return id;
        Block b = state.getBlock();
        try {
            return Block.getIdFromBlock(b) << 4 | b.getMetaFromState(state) & 15;
        } catch (RuntimeException e) {
            return Block.getIdFromBlock(b) << 4;
        }
    }

    /** Runtime biome ids of the 256 columns, through the getter id extenders keep working. */
    private static int[] biomesOf(Chunk c) {
        BiomeProvider provider = c.getWorld().getBiomeProvider();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] out = new int[256];
        int bx = c.x << 4, bz = c.z << 4;
        try {
            for (int i = 0; i < 256; i++) {
                Biome b = c.getBiome(pos.setPos(bx + (i & 15), 64, bz + (i >> 4)), provider);
                out[i] = b == null ? 1 : Biome.getIdForBiome(b);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    /** An empty chunk to fill from a save; every section present (air until set). */
    static RawChunk forImport(int cx, int cz, boolean sky, int[] biomes) {
        RawChunk r = new RawChunk(cx, cz, sky, biomes);
        r.present = 0xFFFF;
        return r;
    }

    /** One section from a save: runtime state ids (already remapped from the save's ids). */
    void setImported(int y, int[] runtimeStates, byte[] blockLight, byte[] skyLight) {
        states[y] = runtimeStates;
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
        for (int i = 0; i < 256; i++) biomeIds[i] = mapper.lodBiome(biomes == null || biomes.length < 256 ? 1 : biomes[i]);
        int defaultSky = sky ? 15 : 0;
        Column col = COLUMN.get();
        int[] states = col.states;
        byte[] blockL = col.block, skyL = col.sky;
        // Column index Y << 8 | z << 4 | x, the section index i plus y << 12.
        for (int y = 0; y < 16; y++) {
            if ((present & 1 << y) == 0) continue;
            int base = y << 12;
            int[] st = this.states[y];
            if (st == null) {
                java.util.Arrays.fill(states, base, base + 4096, 0);
                java.util.Arrays.fill(blockL, base, base + 4096, (byte) 0);
                java.util.Arrays.fill(skyL, base, base + 4096, (byte) defaultSky);
                continue;
            }
            byte[] bl = blockLight[y], sl = skyLight[y];
            int lastStorage = -1, lastLod = 0;
            for (int i = 0; i < 4096; i++) {
                int storage = st[i];
                if (storage != lastStorage) {
                    lastStorage = storage;
                    lastLod = mapper.lodState(storage);
                }
                states[base + i] = lastLod;
                blockL[base + i] = (byte) (bl == null ? 0 : nibble(bl, i));
                skyL[base + i] = (byte) (sl == null ? defaultSky : nibble(sl, i));
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
