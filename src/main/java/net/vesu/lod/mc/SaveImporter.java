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
    private final short[] remap = new short[4096];
    private final ConcurrentLinkedQueue<File> regions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger regionsLeft = new AtomicInteger();
    private final AtomicLong chunks = new AtomicLong(), progressTotal = new AtomicLong();
    private volatile boolean cancelled;

    SaveImporter(LodSession session, File saveDir, int dimension) {
        this.session = session;
        File dimDir = dimension == 0 ? saveDir : new File(saveDir, "DIM" + dimension);
        this.regionDir = new File(dimDir, "region");
        buildRemap(new File(saveDir, "level.dat"));
        File[] files = regionDir.listFiles((d, n) -> n.startsWith("r.") && n.endsWith(".mca"));
        if (files != null) {
            Arrays.sort(files);
            regions.addAll(Arrays.asList(files));
        }
        regionsLeft.set(regions.size());
        progressTotal.set(1024L * regions.size());
        Initializer.LOGGER.info("[lod] importing {} regions from {}", regions.size(), regionDir);
    }

    private void buildRemap(File levelDat) {
        for (int i = 0; i < 4096; i++) remap[i] = (short) i;
        if (!levelDat.isFile()) return;
        try {
            NBTTagCompound root = CompressedStreamTools.readCompressed(new java.io.FileInputStream(levelDat));
            NBTTagCompound fml = root.getCompoundTag("FML");
            NBTTagCompound blocks = fml.getCompoundTag("Registries").getCompoundTag("minecraft:blocks");
            NBTTagList ids = blocks.getTagList("ids", 10);
            if (ids.isEmpty()) return;
            Arrays.fill(remap, (short) 0);
            Set<String> missing = new HashSet<>();
            for (int i = 0; i < ids.tagCount(); i++) {
                NBTTagCompound e = ids.getCompoundTagAt(i);
                String name = e.getString("K");
                int saveId = e.getInteger("V");
                if (saveId < 0 || saveId >= 4096) continue;
                Block b = Block.REGISTRY.getObject(new ResourceLocation(name));
                if (b == null || !Block.REGISTRY.getNameForObject(b).toString().equals(name)) {
                    if (missing.add(name)) Initializer.LOGGER.warn("[lod] import: unknown block {} becomes air", name);
                    continue;
                }
                remap[saveId] = (short) Block.getIdFromBlock(b);
            }
        } catch (IOException | RuntimeException e) {
            Initializer.LOGGER.warn("[lod] import: level.dat ids unreadable ({}); vanilla ids assumed", e.toString());
            for (int i = 0; i < 4096; i++) remap[i] = (short) i;
        }
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
        return !regions.isEmpty() && session.ingest.queued() < 2000;
    }

    @Override
    public boolean runOne() {
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

    private void readRegion(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long size = raf.length();
            if (size < 8192) return;
            byte[] header = new byte[4096];
            raf.readFully(header);
            for (int i = 0; i < 1024 && !cancelled; i++) {
                int e = (header[i * 4] & 255) << 24 | (header[i * 4 + 1] & 255) << 16 | (header[i * 4 + 2] & 255) << 8 | header[i * 4 + 3] & 255;
                if (e == 0) continue;
                long start = (long) (e >>> 8) * 4096;
                int sectors = e & 0xFF;
                if (start < 8192 || start + 5 > size || start + (long) sectors * 4096 > size) continue;
                raf.seek(start);
                int len = raf.readInt();
                if (len <= 1 || start + 4 + len > size) continue;
                int type = raf.readByte();
                byte[] data = new byte[len - 1];
                raf.readFully(data);
                InputStream in = new ByteArrayInputStream(data);
                in = type == 1 ? new GZIPInputStream(in) : type == 2 ? new InflaterInputStream(in) : null;
                if (in == null) continue;
                NBTTagCompound root;
                try (DataInputStream din = new DataInputStream(new BufferedInputStream(in))) {
                    root = CompressedStreamTools.read(din, NBTSizeTracker.INFINITE);
                } catch (IOException | RuntimeException ex) {
                    continue;
                }
                convert(root.getCompoundTag("Level"));
            }
        }
    }

    private void convert(NBTTagCompound level) {
        if (!level.getBoolean("TerrainPopulated")) return;
        int cx = level.getInteger("xPos"), cz = level.getInteger("zPos");
        byte[] biomes = level.getByteArray("Biomes");
        if (biomes.length != 256) biomes = null;
        RawChunk c = RawChunk.forImport(cx, cz, session.hasSky(), biomes);
        NBTTagList sections = level.getTagList("Sections", 10);
        for (int i = 0; i < sections.tagCount(); i++) {
            NBTTagCompound s = sections.getCompoundTagAt(i);
            int y = s.getByte("Y");
            if (y < 0 || y > 15) continue;
            byte[] blocks = s.getByteArray("Blocks");
            if (blocks.length != 4096) continue;
            byte[] add = s.hasKey("Add", 7) ? s.getByteArray("Add") : null;
            short[] ids = new short[4096];
            for (int k = 0; k < 4096; k++) {
                int id = blocks[k] & 255;
                if (add != null && add.length == 2048) id |= ((add[k >> 1] >> ((k & 1) << 2)) & 15) << 8;
                ids[k] = remap[id];
            }
            byte[] meta = s.getByteArray("Data"), bl = s.getByteArray("BlockLight"), sl = s.getByteArray("SkyLight");
            c.setImported(y, ids, meta.length == 2048 ? meta : null, bl.length == 2048 ? bl : null, sl.length == 2048 ? sl : null);
        }
        chunks.incrementAndGet();
        session.ingest(c);
    }
}
