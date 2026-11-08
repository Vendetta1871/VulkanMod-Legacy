package net.vesu.lod.mc;

import net.minecraft.block.Block;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.vesu.Initializer;
import net.vesu.lod.WorkerPool;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Save import (AUDIT 5.8): region files of a 1.12 save, read-only, one region per job at
 * the lowest worker weight. Numeric block ids are the save's: they map to names through
 * level.dat's FML registry and from there to this game's ids. Unknown names become air.
 */
final class SaveImporter implements WorkerPool.Service {
    private final LodSession session;
    private final File regionDir;
    /** Save block id -> runtime block id (0 = unknown, becomes air). Grows past 4096 for id extenders. */
    private int[] remap = new int[4096];
    private final ConcurrentLinkedQueue<File> regions = new ConcurrentLinkedQueue<>();
    /** Single columns the generation producer found on disk (AUDIT 5.7), {@code x << 32 | z}. */
    private final ConcurrentLinkedQueue<Long> singles = new ConcurrentLinkedQueue<>();
    private LodGenerator generator;
    private final AtomicInteger regionsLeft = new AtomicInteger();
    private final AtomicLong chunks = new AtomicLong(), progressTotal = new AtomicLong();
    private volatile boolean cancelled;

    SaveImporter(LodSession session, File saveDir, int dimension) {
        this(session, saveDir, dimension, true);
    }

    private SaveImporter(LodSession session, File saveDir, int dimension, boolean all) {
        this.session = session;
        File dimDir = dimension == 0 ? saveDir : new File(saveDir, "DIM" + dimension);
        this.regionDir = new File(dimDir, "region");
        buildRemap(new File(saveDir, "level.dat"));
        File[] files = all ? regionDir.listFiles((d, n) -> n.startsWith("r.") && n.endsWith(".mca")) : null;
        if (files != null) {
            Arrays.sort(files);
            regions.addAll(Arrays.asList(files));
        }
        regionsLeft.set(regions.size());
        progressTotal.set(1024L * regions.size());
        if (all) Initializer.LOGGER.info("[lod] importing {} regions from {}", regions.size(), regionDir);
    }

    private void buildRemap(File levelDat) {
        for (int i = 0; i < remap.length; i++) remap[i] = i;
        if (!levelDat.isFile()) return;
        try {
            NBTTagCompound root = CompressedStreamTools.readCompressed(new java.io.FileInputStream(levelDat));
            NBTTagCompound fml = root.getCompoundTag("FML");
            NBTTagCompound blocks = fml.getCompoundTag("Registries").getCompoundTag("minecraft:blocks");
            NBTTagList ids = blocks.getTagList("ids", 10);
            if (ids.isEmpty()) return;
            int max = 4095;
            for (int i = 0; i < ids.tagCount(); i++) max = Math.max(max, ids.getCompoundTagAt(i).getInteger("V"));
            remap = new int[max + 1];
            Set<String> missing = new HashSet<>();
            for (int i = 0; i < ids.tagCount(); i++) {
                NBTTagCompound e = ids.getCompoundTagAt(i);
                String name = e.getString("K");
                int saveId = e.getInteger("V");
                if (saveId < 0 || saveId >= remap.length) continue;
                Block b = Block.REGISTRY.getObject(new ResourceLocation(name));
                if (b == null || !Block.REGISTRY.getNameForObject(b).toString().equals(name)) {
                    if (missing.add(name)) Initializer.LOGGER.warn("[lod] import: unknown block {} becomes air", name);
                    continue;
                }
                remap[saveId] = Block.getIdFromBlock(b);
            }
        } catch (IOException | RuntimeException e) {
            Initializer.LOGGER.warn("[lod] import: level.dat ids unreadable ({}); vanilla ids assumed", e.toString());
            remap = new int[4096];
            for (int i = 0; i < 4096; i++) remap[i] = i;
        }
    }

    /** A save state id ({@code saveBlockId << 4 | meta}) as a runtime state id; unknown blocks are air. */
    private int runtimeState(int saveState) {
        int block = saveState >>> 4;
        int runtime = block < remap.length ? remap[block] : 0;
        return runtime == 0 ? 0 : runtime << 4 | (saveState & 15);
    }

    private static int nibble(byte[] a, int i) {
        return (a[i >> 1] >> ((i & 1) << 2)) & 15;
    }

    /** Reads one column from its region file on a worker; ingest marks it done for the generator. */
    void readChunkLater(LodGenerator generator, int cx, int cz) {
        this.generator = generator;
        singles.add((long) cx << 32 | (cz & 0xFFFFFFFFL));
        WorkerPool.signalAll();
    }

    /** No regions queued: an importer kept only for the generation producer's reads. */
    static SaveImporter forReads(LodSession session, File saveDir, int dimension) {
        return new SaveImporter(session, saveDir, dimension, false);
    }

    void cancel() {
        cancelled = true;
        regions.clear();
    }

    boolean done() {
        return regionsLeft.get() <= 0 || cancelled;
    }

    String progress() {
        return String.format("LOD import %d chunks, %d regions left", chunks.get(), regionsLeft.get());
    }

    @Override
    public int weight() {
        return 3;
    }

    @Override
    public boolean hasWork() {
        // Backpressure: wait while ingest holds many chunks already.
        return (!regions.isEmpty() || !singles.isEmpty()) && !session.ingest.full();
    }

    @Override
    public boolean runOne() {
        Long single = singles.poll();
        if (single != null) {
            int cx = (int) (single >> 32), cz = (int) (long) single;
            boolean ok;
            try {
                ok = readSingle(cx, cz);
            } catch (IOException | RuntimeException e) {
                ok = false; // a chunk being written right now
            }
            if (!ok && generator != null) generator.readFailed(cx, cz);
            return true;
        }
        File f = regions.poll();
        if (f == null) return false;
        try {
            readRegion(f);
        } catch (IOException | RuntimeException e) {
            Initializer.LOGGER.warn("[lod] import: {} unreadable: {}", f.getName(), e.toString());
        } finally {
            regionsLeft.decrementAndGet();
            progressTotal.addAndGet(-1024);
        }
        if (regionsLeft.get() == 0) Initializer.LOGGER.info("[lod] import done: {} chunks", chunks.get());
        return true;
    }

    private boolean readSingle(int cx, int cz) throws IOException {
        File f = new File(regionDir, "r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        if (!f.isFile()) return false;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            int i = (cz & 31) << 5 | (cx & 31);
            return readEntry(raf, raf.length(), i, generator);
        }
    }

    private void readRegion(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long size = raf.length();
            if (size < 8192) return;
            for (int i = 0; i < 1024 && !cancelled; i++) readEntry(raf, size, i, null);
        }
    }

    /** Reads entry {@code i} of an open region file; true when a populated chunk was ingested. */
    private boolean readEntry(RandomAccessFile raf, long size, int i, LodGenerator doneBy) throws IOException {
        if (size < 8192) return false;
        raf.seek(i * 4L);
        int e = raf.readInt();
        if (e == 0) return false;
        long start = (long) (e >>> 8) * 4096;
        int sectors = e & 0xFF;
        if (start < 8192 || start + 5 > size || start + (long) sectors * 4096 > size) return false;
        raf.seek(start);
        int len = raf.readInt();
        if (len <= 1 || start + 4 + len > size) return false;
        int type = raf.readByte();
        byte[] data = new byte[len - 1];
        raf.readFully(data);
        InputStream in = new ByteArrayInputStream(data);
        in = type == 1 ? new GZIPInputStream(in) : type == 2 ? new InflaterInputStream(in) : null;
        if (in == null) return false;
        NBTTagCompound root;
        try (DataInputStream din = new DataInputStream(new BufferedInputStream(in))) {
            root = CompressedStreamTools.read(din, NBTSizeTracker.INFINITE);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
        return convert(root.getCompoundTag("Level"), doneBy);
    }

    private boolean convert(NBTTagCompound level, LodGenerator doneBy) {
        if (!level.getBoolean("TerrainPopulated")) return false;
        int cx = level.getInteger("xPos"), cz = level.getInteger("zPos");
        // Vanilla stores a byte per column, id extenders (RoughlyEnoughIDs) an int.
        int[] biomes = null;
        if (level.hasKey("Biomes", 11)) {
            int[] b = level.getIntArray("Biomes");
            if (b.length == 256) biomes = b;
        } else if (level.hasKey("Biomes", 7)) {
            byte[] b = level.getByteArray("Biomes");
            if (b.length == 256) {
                biomes = new int[256];
                for (int i = 0; i < 256; i++) biomes[i] = b[i] & 255;
            }
        }
        RawChunk c = RawChunk.forImport(cx, cz, session.hasSky(), biomes);
        NBTTagList sections = level.getTagList("Sections", 10);
        for (int i = 0; i < sections.tagCount(); i++) {
            NBTTagCompound s = sections.getCompoundTagAt(i);
            int y = s.getByte("Y");
            if (y < 0 || y > 15) continue;
            byte[] blocks = s.getByteArray("Blocks");
            if (blocks.length != 4096) continue;
            byte[] add = s.hasKey("Add", 7) ? s.getByteArray("Add") : null;
            if (add != null && add.length != 2048) add = null;
            byte[] meta = s.getByteArray("Data");
            if (meta.length != 2048) meta = null;
            // RoughlyEnoughIDs: Blocks and Data together hold an index into the section's
            // Palette of save state ids (Add, when present, its next four bits).
            int[] palette = s.hasKey("Palette", 11) ? s.getIntArray("Palette") : null;
            int[] states = new int[4096];
            for (int k = 0; k < 4096; k++) {
                int saveState;
                if (palette != null) {
                    int index = (blocks[k] & 255) << 4 | (meta == null ? 0 : nibble(meta, k))
                            | (add == null ? 0 : nibble(add, k) << 12);
                    saveState = index < palette.length ? palette[index] : 0;
                } else {
                    int id = (blocks[k] & 255) | (add == null ? 0 : nibble(add, k) << 8);
                    saveState = id << 4 | (meta == null ? 0 : nibble(meta, k));
                }
                states[k] = runtimeState(saveState);
            }
            byte[] bl = s.getByteArray("BlockLight"), sl = s.getByteArray("SkyLight");
            c.setImported(y, states, bl.length == 2048 ? bl : null, sl.length == 2048 ? sl : null);
        }
        chunks.incrementAndGet();
        c.doneBy = doneBy;
        session.ingest(c);
        return true;
    }
}
