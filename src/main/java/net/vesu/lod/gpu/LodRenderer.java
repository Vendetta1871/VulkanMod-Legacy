package net.vesu.lod.gpu;

import net.vesu.Initializer;
import net.vesu.lod.bake.FaceBaker;
import net.vesu.lod.mc.Bakery;
import net.vesu.vulkan.Drawer;
import net.vesu.vulkan.FrameTargets;
import net.vesu.vulkan.Pipeline;
import net.vesu.vulkan.ShaderSPIRVUtils;
import net.vesu.vulkan.Vulkan;
import net.vesu.vulkan.memory.MemoryManager;
import net.vesu.vulkan.texture.VTextureSelector;
import net.vesu.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * GPU side of the LOD terrain (AUDIT 5.15), first-pixels form (L5): the quad arena,
 * section and model records, the LOD atlas, and one indirect multi-draw per pass whose
 * commands the CPU selection writes. Render thread only.
 */
public final class LodRenderer {
    public static final int MAX_SECTIONS = 1 << 17;
    public static final int MAX_DRAWS = 1 << 18;
    /**
     * A VkDrawIndexedIndirectCommand: the shared quad index buffer, vertexOffset = 4 x the
     * first quad, so each quad runs the vertex stage for its 4 corners, not 6 vertices.
     */
    private static final int CMD = 20;
    /** Quads one command can draw: the counts of a face group are 16 bits. */
    private static final int MAX_QUADS_PER_DRAW = 1 << 16;
    private static final int UBO_SIZE = 256;
    private static final long STAGING = 32L << 20;
    private static final int COVER_BYTES = 70 * 70 * 16 / 8 + 64;
    private static final int ATLAS_W = 12288, ATLAS_MIPS = 4;

    private final VkDevice device = Vulkan.getDevice();
    private final int frames = Drawer.framesInFlight();
    private final int maxDrawIndirect;

    private long arena, arenaAlloc;
    private final QuadAllocator quads;
    private long sectionsBuf, sectionsAlloc, sectionsPtr;
    private long modelsBuf, modelsAlloc, modelsPtr;
    private long tintsBuf, tintsAlloc, tintsPtr;
    /** Per model: the pack's block.properties attribute of its block tag (record word 8). */
    private long packIdsBuf, packIdsAlloc, packIdsPtr;
    private int modelCount;
    private final long[] uboBuf, uboAlloc, uboPtr, cmdBuf, cmdAlloc, cmdPtr, coverBuf, coverAlloc, coverPtr, stagingBuf, stagingAlloc, stagingPtr;

    // GPU culling (L6): per-slot meta, per-frame candidates, device-written commands and counts, Hi-Z.
    private long metaBuf, metaAlloc, metaPtr;
    private final long[] candBuf, candAlloc, candPtr, gpuCmdBuf, gpuCmdAlloc, countBuf, countAlloc;
    private long hizImage, hizAlloc, hizView, hizSampler, depthSampler;
    private long[] hizLevelViews = new long[0];
    private int hizW, hizH, hizLevels;
    private long hizLayout;
    private long[][] hizSets;
    private net.vesu.vulkan.ComputePipeline cullPipeline, hizPipeline;
    private int opaqueCandidates, translucentCandidates;
    public final boolean gpuCull;

    private VulkanImage atlas;
    private int atlasRows;
    private final java.util.List<int[][]> tiles = new java.util.ArrayList<>();
    private long sampler;

    private long setLayout, pool, pipelineLayout, opaquePipeline, translucentPipeline;
    private final long[] sets;
    private int pipelineDepthFormat;

    // Slots and ranges freed this frame return once every frame that may read them is done.
    private final ArrayDeque<long[]> pendingFrees = new ArrayDeque<>();
    private final int[] freeSlots = new int[MAX_SECTIONS];
    private int freeSlotCount, nextSlot;
    private long frameNo;

    private int frame;
    private long stagingUsed;
    private VkCommandBuffer transferCmd;
    private int opaqueDraws, translucentDraws;
    private long[] translucentSort = new long[1024];
    private int[] translucentCmd = new int[1024 * 4];
    private long[] translucentQuad = new long[1024];
    private int translucentCount;
    private boolean uploadedThisFrame;

    public LodRenderer(long arenaBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), props);
            maxDrawIndirect = (int) Math.max(1, Math.min(Integer.MAX_VALUE, props.limits().maxDrawIndirectCount() & 0xFFFFFFFFL));
            LongBuffer pb = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            MemoryManager.createBuffer(arenaBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pb, pa);
            arena = pb.get(0);
            arenaAlloc = pa.get(0);
            quads = new QuadAllocator(arenaBytes / 8);

            long[] r = hostBuffer(stack, (long) MAX_SECTIONS * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            sectionsBuf = r[0]; sectionsAlloc = r[1]; sectionsPtr = r[2];
            r = hostBuffer(stack, 65536L * 64, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            modelsBuf = r[0]; modelsAlloc = r[1]; modelsPtr = r[2];
            MemoryUtil.memSet(modelsPtr, 0, 65536L * 64);
            r = hostBuffer(stack, 65536L * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            packIdsBuf = r[0]; packIdsAlloc = r[1]; packIdsPtr = r[2];
            r = hostBuffer(stack, (long) Bakery.TINT_STRIDE * Bakery.TINT_SLOTS * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            tintsBuf = r[0]; tintsAlloc = r[1]; tintsPtr = r[2];
        }
        gpuCull = Vulkan.getDeviceInfo().features.drawIndirectCount && net.vesu.Env.get("VESU_LOD_CPU") == null;
        candBuf = new long[frames]; candAlloc = new long[frames]; candPtr = new long[frames];
        gpuCmdBuf = new long[frames]; gpuCmdAlloc = new long[frames];
        countBuf = new long[frames]; countAlloc = new long[frames];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] r = hostBuffer(stack, (long) MAX_SECTIONS * 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            metaBuf = r[0]; metaAlloc = r[1]; metaPtr = r[2];
            for (int i = 0; i < frames; i++) {
                r = hostBuffer(stack, (long) MAX_SECTIONS * 2 * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                candBuf[i] = r[0]; candAlloc[i] = r[1]; candPtr[i] = r[2];
                LongBuffer pb = stack.mallocLong(1);
                PointerBuffer pa = stack.mallocPointer(1);
                MemoryManager.createBuffer((long) MAX_DRAWS * CMD * 2, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pb, pa);
                gpuCmdBuf[i] = pb.get(0); gpuCmdAlloc[i] = pa.get(0);
                MemoryManager.createBuffer(64, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pb, pa);
                countBuf[i] = pb.get(0); countAlloc[i] = pa.get(0);
            }
        }
        uboBuf = new long[frames]; uboAlloc = new long[frames]; uboPtr = new long[frames];
        cmdBuf = new long[frames]; cmdAlloc = new long[frames]; cmdPtr = new long[frames];
        coverBuf = new long[frames]; coverAlloc = new long[frames]; coverPtr = new long[frames];
        stagingBuf = new long[frames]; stagingAlloc = new long[frames]; stagingPtr = new long[frames];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < frames; i++) {
                long[] r = hostBuffer(stack, UBO_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                uboBuf[i] = r[0]; uboAlloc[i] = r[1]; uboPtr[i] = r[2];
                r = hostBuffer(stack, (long) MAX_DRAWS * CMD * 2, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
                cmdBuf[i] = r[0]; cmdAlloc[i] = r[1]; cmdPtr[i] = r[2];
                r = hostBuffer(stack, COVER_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                coverBuf[i] = r[0]; coverAlloc[i] = r[1]; coverPtr[i] = r[2];
                r = hostBuffer(stack, STAGING, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                stagingBuf[i] = r[0]; stagingAlloc[i] = r[1]; stagingPtr[i] = r[2];
            }
        }
        createAtlas(8);
        createSampler();
        createLayout();
        sets = new long[frames];
        allocateSets();
        Initializer.LOGGER.info("[lod] renderer: arena {} MB, {} frames", arenaBytes >> 20, frames);
    }

    private static long[] hostBuffer(MemoryStack stack, long size, int usage) {
        LongBuffer pb = stack.mallocLong(1);
        PointerBuffer pa = stack.mallocPointer(1);
        MemoryManager.createBufferHostAccess(size, usage, pb, pa);
        long ptr = MemoryManager.Map(pa.get(0)).get(0);
        return new long[]{pb.get(0), pa.get(0), ptr};
    }

    // ---- Atlas ----

    private void createAtlas(int rows) {
        VulkanImage old = atlas;
        atlas = new VulkanImage(ATLAS_MIPS, ATLAS_W, rows * 32, 4, false, true);
        atlasRows = rows;
        if (old != null) {
            old.free();   // defers the destruction itself
            for (int m = 0; m < tiles.size(); m++) if (tiles.get(m) != null) uploadTiles(m, tiles.get(m));
        }
    }

    private void createSampler() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0).maxLod(ATLAS_MIPS - 1);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSampler(device, info, null, p), "sampler");
            sampler = p.get(0);
        }
    }

    /** A baked model: its 64-byte record and its six face tiles with mips. */
    public void uploadModel(Bakery.Upload u) {
        long base = modelsPtr + (long) u.model() * 64;
        for (int i = 0; i < 16; i++) MemoryUtil.memPutInt(base + i * 4L, u.record()[i]);
        MemoryUtil.memPutInt(packIdsPtr + u.model() * 4L, net.vesu.shader.PackIds.block(u.record()[8]));
        modelCount = Math.max(modelCount, u.model() + 1);
        while (tiles.size() <= u.model()) tiles.add(null);
        tiles.set(u.model(), u.tiles());
        int rowsNeeded = (u.model() >> 8) + 1;
        if (rowsNeeded > atlasRows) {
            createAtlas(Math.max(rowsNeeded, atlasRows * 2));
            allocateSets();
        } else {
            uploadTiles(u.model(), u.tiles());
        }
    }

    private void uploadTiles(int model, int[][] faceTiles) {
        int cx = (model & 0xFF) * 48, cy = (model >> 8) * 32;
        int[] level = new int[48 * 32];
        for (int f = 0; f < 6; f++) {
            int ox = (f % 3) * 16, oy = (f / 3) * 16;
            for (int y = 0; y < 16; y++) System.arraycopy(faceTiles[f], y * 16, level, (oy + y) * 48 + ox, 16);
        }
        int w = 48, h = 32;
        for (int m = 0; m < ATLAS_MIPS; m++) {
            ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
            for (int i = 0; i < w * h; i++) buf.putInt(i * 4, level[i]);
            atlas.uploadSubTextureAsync(m, w, h, cx >> m, cy >> m, 4, 0, 0, w, buf);
            MemoryUtil.memFree(buf);
            if (m + 1 < ATLAS_MIPS) {
                level = downsample(level, w, h);
                w >>= 1;
                h >>= 1;
            }
        }
    }

    /** 2x2 box filter weighted by alpha, so transparent texels do not darken the edges. */
    private static int[] downsample(int[] src, int w, int h) {
        int nw = w >> 1, nh = h >> 1;
        int[] dst = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int r = 0, g = 0, b = 0, a = 0, wsum = 0;
                for (int k = 0; k < 4; k++) {
                    int p = src[(2 * y + (k >> 1)) * w + 2 * x + (k & 1)];
                    int pa = p >>> 24;
                    a += pa;
                    r += (p & 255) * pa;
                    g += (p >> 8 & 255) * pa;
                    b += (p >> 16 & 255) * pa;
                    wsum += pa;
                }
                if (wsum == 0) {
                    dst[y * nw + x] = 0;
                } else {
                    dst[y * nw + x] = (a / 4) << 24 | (b / wsum) << 16 | (g / wsum) << 8 | (r / wsum);
                }
            }
        }
        return dst;
    }

    public void uploadTints(int[] table) {
        for (int i = 0; i < table.length; i++) MemoryUtil.memPutInt(tintsPtr + i * 4L, table[i]);
    }

    // ---- Sections and quads ----

    public int allocSlot() {
        if (freeSlotCount > 0) return freeSlots[--freeSlotCount];
        return nextSlot < MAX_SECTIONS ? nextSlot++ : -1;
    }

    public void writeSection(int slot, int ox, int oy, int oz, int lvl) {
        long p = sectionsPtr + (long) slot * 16;
        MemoryUtil.memPutInt(p, ox);
        MemoryUtil.memPutInt(p + 4, oy);
        MemoryUtil.memPutInt(p + 8, oz);
        MemoryUtil.memPutInt(p + 12, lvl);
    }

    /** Culling data of a slot: quad start, AABB and the eight group counts. */
    public void writeMeta(int slot, long quadStart, int aabb, int[] counts) {
        long p = metaPtr + (long) slot * 32;
        MemoryUtil.memPutInt(p, (int) quadStart);
        MemoryUtil.memPutInt(p + 4, aabb);
        for (int g = 0; g < 8; g++) {
            MemoryUtil.memPutShort(p + 8 + g * 2L, (short) Math.min(0xFFFF, counts[g]));
        }
        MemoryUtil.memPutLong(p + 24, 0);
    }

    /** GPU path: this frame's candidate sections, opaque ones then translucent ones far to near. */
    public void setCandidates(int[] opaque, int nOpaque, int[] translucent, int nTranslucent) {
        int n = Math.min(nOpaque, MAX_SECTIONS);
        int m = Math.min(nTranslucent, MAX_SECTIONS);
        long p = candPtr[frame];
        for (int i = 0; i < n; i++) MemoryUtil.memPutInt(p + i * 4L, opaque[i]);
        for (int i = 0; i < m; i++) MemoryUtil.memPutInt(p + ((long) MAX_SECTIONS + i) * 4L, translucent[i]);
        opaqueCandidates = n;
        translucentCandidates = m;
        traversed = false;
    }

    /**
     * The traversal wrote this frame's candidates and their counts on the GPU; the cull pass
     * covers at most {@code bound} of each kind.
     */
    public void traversedCandidates(int bound) {
        traversed = true;
        candidateBound = Math.min(bound, MAX_SECTIONS);
    }

    private boolean traversed;
    /** The traversal sorts at most this many translucent candidates (tsort.comp). */
    private static final int MAX_SORTED_TRANSLUCENT = 2048;
    private int candidateBound;

    // ---- GPU traversal (AUDIT 5.11) ----

    private LodTraversal traversal;

    public LodTraversal traversal() {
        if (traversal == null) traversal = new LodTraversal();
        return traversal;
    }

    /** The set of this frame and its layout, for the traversal (uniforms, coverage, candidates, counts). */
    public void recordTraversal(VkCommandBuffer cmd, int rootCount, int frameId, float pixelScale, float quality,
                                float lodBlocks, int requestBudget) {
        // The set holds the Hi-Z too: it must exist before the set is written.
        ensureHiz(FrameTargets.scene.width(), FrameTargets.scene.height());
        if (!setsFresh) {
            for (int i = 0; i < frames; i++) writeSet(i);
            setsFresh = true;
        }
        traversal().record(cmd, frame, setLayout, sets[frame], countBuf[frame], rootCount, frameId, pixelScale, quality,
                lodBlocks, requestBudget);
    }

    public int frameSlot() {
        return frame;
    }

    public void freeSlot(int slot) {
        if (slot >= 0) pendingFrees.add(new long[]{frameNo, 0, slot, 0});
    }

    public long allocQuads(int n) {
        return quads.alloc(n);
    }

    public void freeQuads(long start, int n) {
        if (start >= 0 && n > 0) pendingFrees.add(new long[]{frameNo, 1, start, n});
    }

    public long arenaUsedBytes() {
        return quads.used() * 8;
    }

    public long arenaCapacityBytes() {
        return quads.capacity() * 8;
    }

    /** Stages quads for the arena; false when this frame's staging is full (try next frame). */
    public boolean upload(long[] data, int from, int count, long start) {
        if (count == 0) return true;
        long bytes = (long) count * 8;
        if (stagingUsed + bytes > STAGING) return false;
        if (transferCmd == null) {
            transferCmd = Drawer.getFrameTransferCmd();
            if (transferCmd == null) return false;
        }
        long src = stagingPtr[frame] + stagingUsed;
        for (int i = 0; i < count; i++) MemoryUtil.memPutLong(src + i * 8L, data[from + i]);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(stagingUsed).dstOffset(start * 8).size(bytes);
            vkCmdCopyBuffer(transferCmd, stagingBuf[frame], arena, region);
        }
        stagingUsed += bytes;
        uploadedThisFrame = true;
        return true;
    }

    // ---- Frame ----

    public void beginFrame() {
        frame = Drawer.getCurrentFrame();
        frameNo++;
        while (!pendingFrees.isEmpty() && pendingFrees.peek()[0] + frames + 1 <= frameNo) {
            long[] f = pendingFrees.poll();
            if (f[1] == 0) freeSlots[freeSlotCount++] = (int) f[2];
            else quads.free(f[2], f[3]);
        }
        stagingUsed = 0;
        transferCmd = null;
        uploadedThisFrame = false;
        opaqueDraws = 0;
        shadowDraws = 0;
        translucentDraws = 0;
        translucentCount = 0;
    }

    /** Makes this frame's arena copies visible to the draws (end of the transfer commands). */
    public void endUploads() {
        if (!uploadedThisFrame || transferCmd == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(transferCmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, b, null, null);
        }
        uploadedThisFrame = false;
    }

    public void addOpaque(long firstQuad, int count, int slot) {
        if (opaqueDraws >= MAX_DRAWS || count <= 0) return;
        putCommand(cmdPtr[frame] + (long) opaqueDraws * CMD, count, firstQuad, slot);
        opaqueDraws++;
    }

    /** Draws into the pack's shadow map, at the end of the translucent region (lodPackShadows). */
    private static final int SHADOW_DRAWS = 16384;
    private static final long SHADOW_OFFSET = (long) (2 * MAX_DRAWS - SHADOW_DRAWS) * CMD;
    private int shadowDraws;

    public void addShadow(long firstQuad, int count, int slot) {
        if (shadowDraws >= SHADOW_DRAWS || count <= 0) return;
        putCommand(cmdPtr[frame] + SHADOW_OFFSET + (long) shadowDraws * CMD, count, firstQuad, slot);
        shadowDraws++;
    }

    private static void putCommand(long p, int quads, long firstQuad, int slot) {
        MemoryUtil.memPutInt(p, quads * 6);
        MemoryUtil.memPutInt(p + 4, 1);
        MemoryUtil.memPutInt(p + 8, 0);
        MemoryUtil.memPutInt(p + 12, (int) (firstQuad * 4));
        MemoryUtil.memPutInt(p + 16, slot);
    }

    /** The translucent draws, far to near, into the translucent region; their count. */
    private int writeTranslucent() {
        java.util.Arrays.sort(translucentSort, 0, translucentCount);
        long base = cmdPtr[frame] + (long) MAX_DRAWS * CMD;
        int n = Math.min(translucentCount, MAX_DRAWS - SHADOW_DRAWS);
        for (int k = 0; k < n; k++) {
            int i = (int) translucentSort[k];
            putCommand(base + (long) k * CMD, translucentCmd[i * 4], translucentQuad[i], translucentCmd[i * 4 + 2]);
        }
        return n;
    }

    /** The shared quad index buffer (0,1,2, 2,3,0 per 4 vertices), sized for one command. */
    private void bindQuadIndices(VkCommandBuffer cmd) {
        net.vesu.vulkan.memory.IndexBuffer ib = Drawer.getInstance().getQuadsIndexBuffer().getIndexBuffer(MAX_QUADS_PER_DRAW * 4);
        vkCmdBindIndexBuffer(cmd, ib.getId(), ib.getOffset(), ib.indexType.vkType);
    }

    /** Translucent draws are sorted far to near before drawing; {@code distance} in blocks. */
    public void addTranslucent(long firstQuad, int count, int slot, float distance) {
        if (count <= 0) return;
        if (translucentCount == translucentSort.length) {
            translucentSort = java.util.Arrays.copyOf(translucentSort, translucentCount * 2);
            translucentCmd = java.util.Arrays.copyOf(translucentCmd, translucentCount * 8);
        }
        if (translucentCount == translucentQuad.length) translucentQuad = java.util.Arrays.copyOf(translucentQuad, translucentCount * 2);
        int i = translucentCount++;
        translucentSort[i] = (long) (Integer.MAX_VALUE - Float.floatToIntBits(Math.max(0, distance))) << 32 | i;
        translucentCmd[i * 4] = count;
        translucentQuad[i] = firstQuad;
        translucentCmd[i * 4 + 2] = slot;
    }

    public int opaqueDraws() {
        return opaqueDraws;
    }

    public int translucentDraws() {
        return translucentCount;
    }

    /**
     * Fills this frame's uniforms and coverage grid. {@code coverBits}: one bit per chunk
     * section of an n x 16 x n grid at chunk (cx, cz), {@code (z * n + x) * 16 + y}.
     */
    public void setFrame(ByteBuffer ubo, int[] coverBits, int coverWords) {
        MemoryUtil.memCopy(MemoryUtil.memAddress(ubo), uboPtr[frame], Math.min(ubo.remaining(), UBO_SIZE));
        for (int i = 0; i < coverWords; i++) MemoryUtil.memPutInt(coverPtr[frame] + i * 4L, coverBits[i]);
    }

    /** Host copies of the model table (64 bytes per model) and the biome tints, for RT. */
    public long modelsPtr() {
        return modelsPtr;
    }

    public long tintsPtr() {
        return tintsPtr;
    }

    public float atlasWidth() {
        return ATLAS_W;
    }

    public float atlasHeight() {
        return atlasRows * 32;
    }

    private void writeSet(int i) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(gpuCull ? 13 : 8, stack);
            long[] bufs = {uboBuf[i], arena, sectionsBuf, modelsBuf, tintsBuf};
            int[] types = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
            for (int b = 0; b < 5; b++) {
                VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack).buffer(bufs[b]).offset(0).range(VK_WHOLE_SIZE);
                w.get(b).sType$Default().dstSet(sets[i]).dstBinding(b).descriptorType(types[b]).descriptorCount(1).pBufferInfo(bi);
            }
            VkDescriptorImageInfo.Buffer ai = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler)
                    .imageView(atlas.getTextureImageView()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            w.get(5).sType$Default().dstSet(sets[i]).dstBinding(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(ai);
            VulkanImage light = VTextureSelector.getLightTexture();
            if (light == null || light.isFreed()) light = VTextureSelector.getWhiteTexture();
            VkDescriptorImageInfo.Buffer li = VkDescriptorImageInfo.calloc(1, stack).sampler(light.getTextureSampler())
                    .imageView(light.getTextureImageView()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            w.get(6).sType$Default().dstSet(sets[i]).dstBinding(6).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(li);
            VkDescriptorBufferInfo.Buffer ci = VkDescriptorBufferInfo.calloc(1, stack).buffer(coverBuf[i]).offset(0).range(VK_WHOLE_SIZE);
            w.get(7).sType$Default().dstSet(sets[i]).dstBinding(7).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).pBufferInfo(ci);
            if (gpuCull) {
                long[] extra = {metaBuf, candBuf[i], gpuCmdBuf[i], countBuf[i]};
                for (int b = 0; b < 4; b++) {
                    VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack).buffer(extra[b]).offset(0).range(VK_WHOLE_SIZE);
                    w.get(8 + b).sType$Default().dstSet(sets[i]).dstBinding(8 + b).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1).pBufferInfo(bi);
                }
                VkDescriptorImageInfo.Buffer hi = VkDescriptorImageInfo.calloc(1, stack).sampler(hizSampler)
                        .imageView(hizView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                w.get(12).sType$Default().dstSet(sets[i]).dstBinding(12).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).pImageInfo(hi);
            }
            vkUpdateDescriptorSets(device, w, null);
        }
    }

    // ---- Pipelines ----

    private void createLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int n = gpuCull ? 13 : 8;
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(n, stack);
            int vf = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT;
            int[] types = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER};
            for (int i = 0; i < n; i++) b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(vf);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p), "set layout");
            setLayout = p.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(frames * 2);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(frames * 20);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(frames * 8);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT).maxSets(frames * 2).pPoolSizes(sizes), null, p), "pool");
            pool = p.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)), null, p), "pipeline layout");
            pipelineLayout = p.get(0);
        }
    }

    private boolean setsFresh;

    private void allocateSets() {
        // A new atlas image needs new descriptors; the old sets may still be in flight, so they
        // are replaced, never rewritten, and freed with a delay.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] old = sets.clone();
            LongBuffer layouts = stack.mallocLong(frames);
            for (int i = 0; i < frames; i++) layouts.put(i, setLayout);
            LongBuffer out = stack.mallocLong(frames);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "descriptor sets");
            for (int i = 0; i < frames; i++) sets[i] = out.get(i);
            boolean any = false;
            for (long s : old) any |= s != 0;
            if (any) {
                long poolHandle = pool;
                MemoryManager.addToFreeable(() -> {
                    try (MemoryStack st = MemoryStack.stackPush()) {
                        vkFreeDescriptorSets(device, poolHandle, st.longs(old));
                    }
                });
            }
        }
        setsFresh = false;
    }

    private void createPipelines() {
        destroyPipelines();
        ShaderSPIRVUtils.SPIRV vs = ShaderSPIRVUtils.compileShaderFile("lod/lod.vsh", ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER);
        ShaderSPIRVUtils.SPIRV fs = ShaderSPIRVUtils.compileShaderFile("lod/lod.fsh", ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        ShaderSPIRVUtils.SPIRV ft = ShaderSPIRVUtils.compileShaderFile("lod/lod.fsh", ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER,
                java.util.Map.of("LOD_TRANSLUCENT", "1"));
        if (vs == null || fs == null || ft == null) throw new IllegalStateException("LOD shaders failed to compile");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long vm = module(stack, vs.bytecode()), fm = module(stack, fs.bytecode()), tm = module(stack, ft.bytecode());
            opaquePipeline = pipeline(stack, vm, fm, false);
            translucentPipeline = pipeline(stack, vm, tm, true);
            vkDestroyShaderModule(device, vm, null);
            vkDestroyShaderModule(device, fm, null);
            vkDestroyShaderModule(device, tm, null);
        }
        pipelineDepthFormat = FrameTargets.depthFormat();
    }

    private long module(MemoryStack stack, ByteBuffer code) {
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, p), "shader module");
        return p.get(0);
    }

    private long pipeline(MemoryStack stack, long vm, long fm, boolean translucent) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(stack.UTF8("main"));
        VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .colorWriteMask(0xF).blendEnable(translucent);
        if (translucent) {
            blend.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
        }
        VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        info.get(0).sType$Default()
                .pStages(stages)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                        .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                        .lineWidth(1.0f))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                        .depthTestEnable(true).depthWriteEnable(!translucent).depthCompareOp(VK_COMPARE_OP_GREATER_OR_EQUAL))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                        .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                .layout(pipelineLayout)
                .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                        .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(FrameTargets.COLOR_FORMAT))
                        .depthAttachmentFormat(FrameTargets.depthFormat()).stencilAttachmentFormat(VK_FORMAT_UNDEFINED).address());
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateGraphicsPipelines(device, Pipeline.getPipelineCache(), info, null, p), "pipeline");
        return p.get(0);
    }

    private void destroyPipelines() {
        if (opaquePipeline != 0) vkDestroyPipeline(device, opaquePipeline, null);
        if (translucentPipeline != 0) vkDestroyPipeline(device, translucentPipeline, null);
        opaquePipeline = translucentPipeline = 0;
    }

    private void bind(VkCommandBuffer cmd, long pipeline) {
        if (opaquePipeline == 0 || pipelineDepthFormat != FrameTargets.depthFormat()) createPipelines();
        if (!setsFresh) {
            for (int i = 0; i < frames; i++) writeSet(i);
            setsFresh = true;
        }
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline == 0 ? opaquePipeline : pipeline);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(sets[frame]), null);
        }
    }

    /** The lightmap image can be replaced; descriptors follow it. */
    public void refreshDescriptors() {
        setsFresh = false;
    }

    public void drawOpaque() {
        if (opaqueDraws == 0) return;
        VkCommandBuffer cmd = Drawer.currentCommandBuffer();
        if (opaquePipeline == 0 || pipelineDepthFormat != FrameTargets.depthFormat()) createPipelines();
        bind(cmd, opaquePipeline);
        drawIndirect(cmd, 0, opaqueDraws);
        Drawer.forgetBoundPipeline(cmd);
    }

    public void drawTranslucent() {
        if (translucentCount == 0) return;
        int n = writeTranslucent();
        VkCommandBuffer cmd = Drawer.currentCommandBuffer();
        if (opaquePipeline == 0 || pipelineDepthFormat != FrameTargets.depthFormat()) createPipelines();
        bind(cmd, translucentPipeline);
        drawIndirect(cmd, (long) MAX_DRAWS * CMD, n);
        Drawer.forgetBoundPipeline(cmd);
    }

    private void drawIndirect(VkCommandBuffer cmd, long offset, int count) {
        bindQuadIndices(cmd);
        if (Vulkan.multiDrawIndirectSupported) {
            int max = maxDrawIndirect;
            for (int done = 0; done < count; done += max) {
                vkCmdDrawIndexedIndirect(cmd, cmdBuf[frame], offset + (long) done * CMD, Math.min(max, count - done), CMD);
            }
        } else {
            for (int i = 0; i < count; i++) vkCmdDrawIndexedIndirect(cmd, cmdBuf[frame], offset + (long) i * CMD, 1, CMD);
        }
    }

    // ---- GPU culling (L6) ----

    private void ensureHiz(int sceneW, int sceneH) {
        int w = Math.max(1, (sceneW + 1) / 2), h = Math.max(1, (sceneH + 1) / 2);
        if (w == hizW && h == hizH && hizImage != 0) return;
        destroyHiz();
        hizW = w;
        hizH = h;
        hizLevels = 1 + (int) Math.floor(Math.log(Math.max(w, h)) / Math.log(2));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pi = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            MemoryManager.createImage(w, h, hizLevels, VK_FORMAT_R32_SFLOAT, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pi, pa);
            hizImage = pi.get(0);
            hizAlloc = pa.get(0);
            hizView = view(stack, 0, hizLevels);
            hizLevelViews = new long[hizLevels];
            for (int l = 0; l < hizLevels; l++) hizLevelViews[l] = view(stack, l, 1);
            if (hizSampler == 0) {
                VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).minLod(0).maxLod(16);
                check(vkCreateSampler(device, si, null, pi), "hiz sampler");
                hizSampler = pi.get(0);
                depthSampler = hizSampler;
            }
            if (hizLayout == 0) {
                VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, stack);
                b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, pi), "hiz layout");
                hizLayout = pi.get(0);
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(frames * 16);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(frames * 16);
                check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                        .maxSets(frames * 16).pPoolSizes(sizes), null, pi), "hiz pool");
                hizPool = pi.get(0);
                LongBuffer layouts = stack.mallocLong(16);
                for (int i = 0; i < 16; i++) layouts.put(i, hizLayout);
                hizSets = new long[frames][16];
                for (int f = 0; f < frames; f++) {
                    LongBuffer out = stack.mallocLong(16);
                    check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(hizPool).pSetLayouts(layouts), out), "hiz sets");
                    for (int i = 0; i < 16; i++) hizSets[f][i] = out.get(i);
                }
            }
        }
        setsFresh = false;
    }

    private long hizPool;

    private long view(MemoryStack stack, int base, int count) {
        VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(stack).sType$Default().image(hizImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R32_SFLOAT);
        vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(base).levelCount(count).baseArrayLayer(0).layerCount(1);
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateImageView(device, vi, null, p), "hiz view");
        return p.get(0);
    }

    private void destroyHiz() {
        if (hizImage == 0) return;
        long img = hizImage, alloc = hizAlloc, full = hizView;
        long[] views = hizLevelViews.clone();
        MemoryManager.addToFreeable(() -> {
            for (long v : views) vkDestroyImageView(device, v, null);
            vkDestroyImageView(device, full, null);
            MemoryManager.freeImage(img, alloc);
        });
        hizImage = 0;
    }

    private void ensureComputePipelines() {
        if (cullPipeline != null) return;
        ShaderSPIRVUtils.SPIRV cull = ShaderSPIRVUtils.compileShaderFile("lod/cull.comp", ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        ShaderSPIRVUtils.SPIRV hiz = ShaderSPIRVUtils.compileShaderFile("lod/hiz.comp", ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        if (cull == null || hiz == null) throw new IllegalStateException("LOD compute shaders failed to compile");
        cullPipeline = new net.vesu.vulkan.ComputePipeline(cull, new long[]{setLayout}, 32);
        hizPipeline = new net.vesu.vulkan.ComputePipeline(hiz, new long[]{hizLayout}, 8);
    }

    /**
     * Outside scene rendering, with the scene depth readable: builds the Hi-Z of this frame's
     * vanilla depth, then culls the candidates and writes the indirect draws.
     */
    public void cull(VkCommandBuffer cmd, long depthView, int sceneW, int sceneH) {
        ensureHiz(sceneW, sceneH);
        ensureComputePipelines();
        if (!setsFresh) {
            for (int i = 0; i < frames; i++) writeSet(i);
            setsFresh = true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Hi-Z: UNDEFINED -> GENERAL, then one dispatch per level.
            VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(0).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(hizImage);
            ib.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(hizLevels).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, ib);
            // The draw counters; the candidate counts (16, 20) come from the traversal or the CPU.
            vkCmdFillBuffer(cmd, countBuf[frame], 0, 16, 0);
            if (!traversed) vkCmdUpdateBuffer(cmd, countBuf[frame], 16, stack.ints(opaqueCandidates, translucentCandidates));

            hizPipeline.bind(cmd);
            int levels = Math.min(hizLevels, 16);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(levels * 2, stack);
            for (int l = 0; l < levels; l++) {
                long set = hizSets[frame][l];
                VkDescriptorImageInfo.Buffer src = VkDescriptorImageInfo.calloc(1, stack).sampler(depthSampler)
                        .imageView(l == 0 ? depthView : hizLevelViews[l - 1])
                        .imageLayout(l == 0 ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorImageInfo.Buffer dst = VkDescriptorImageInfo.calloc(1, stack).imageView(hizLevelViews[l])
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                w.get(l * 2).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).pImageInfo(src);
                w.get(l * 2 + 1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).pImageInfo(dst);
            }
            vkUpdateDescriptorSets(device, w, null);
            int sw = sceneW, sh = sceneH;
            for (int l = 0; l < levels; l++) {
                int lw = Math.max(1, hizW >> l), lh = Math.max(1, hizH >> l);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, hizPipeline.layout(), 0, stack.longs(hizSets[frame][l]), null);
                vkCmdPushConstants(cmd, hizPipeline.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(sw, sh));
                vkCmdDispatch(cmd, (lw + 7) / 8, (lh + 7) / 8, 1);
                VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);
                sw = lw;
                sh = lh;
            }

            int total = traversed ? candidateBound * 2 : opaqueCandidates + translucentCandidates;
            if (total > 0) {
                cullPipeline.bind(cmd);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cullPipeline.layout(), 0, stack.longs(sets[frame]), null);
                ByteBuffer pc = stack.malloc(32);
                pc.putInt(0, opaqueCandidates).putInt(4, translucentCandidates).putInt(8, MAX_DRAWS).putInt(12, levels)
                        .putFloat(16, hizW).putFloat(20, hizH).putFloat(24, sceneW).putFloat(28, sceneH);
                vkCmdPushConstants(cmd, cullPipeline.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
                vkCmdDispatch(cmd, (total + 63) / 64, 1, 1);
            }
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, 0, mb, null, null);
        }
    }

    public void drawOpaqueGpu() {
        if (!traversed && opaqueCandidates == 0) return;
        VkCommandBuffer cmd = Drawer.currentCommandBuffer();
        if (opaquePipeline == 0 || pipelineDepthFormat != FrameTargets.depthFormat()) createPipelines();
        bind(cmd, opaquePipeline);
        bindQuadIndices(cmd);
        VK12.vkCmdDrawIndexedIndirectCount(cmd, gpuCmdBuf[frame], 0, countBuf[frame], 0, MAX_DRAWS, CMD);
        Drawer.forgetBoundPipeline(cmd);
    }

    public void drawTranslucentGpu() {
        if (!traversed && translucentCandidates == 0) return;
        VkCommandBuffer cmd = Drawer.currentCommandBuffer();
        bind(cmd, translucentPipeline);
        long offset = (long) MAX_DRAWS * CMD;
        bindQuadIndices(cmd);
        if (traversed) {
            // One command per translucent candidate, culled ones empty; the count is the traversal's.
            VK12.vkCmdDrawIndexedIndirectCount(cmd, gpuCmdBuf[frame], offset, countBuf[frame], 20, MAX_SORTED_TRANSLUCENT, CMD);
            Drawer.forgetBoundPipeline(cmd);
            return;
        }
        for (int done = 0; done < translucentCandidates; done += maxDrawIndirect) {
            vkCmdDrawIndexedIndirect(cmd, gpuCmdBuf[frame], offset + (long) done * CMD,
                    Math.min(maxDrawIndirect, translucentCandidates - done), CMD);
        }
        Drawer.forgetBoundPipeline(cmd);
    }

    // ---- Under a shader pack (AUDIT 5.16, contract B) ----

    private static final int PACK_UBO_SIZE = 256;
    private long packSetLayout, packPool, packLayout, packOpaque, packTranslucent, packShadow;
    private long[] packSets, packUbo, packUboAlloc, packUboPtr;
    private Object packToken;
    private final ByteBuffer drawBlock = MemoryUtil.memCalloc(net.vesu.vulkan.Drawer.DRAW_BLOCK_SIZE);

    private void ensurePackSets() {
        if (packSetLayout != 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(7, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS);
            for (int i = 1; i < 5; i++) {
                b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS);
            }
            // The coverage grid: distant water drops what vanilla has built.
            b.get(5).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS);
            // The pack's block ids per model (mc_Entity in its own terrain programs).
            b.get(6).binding(6).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p), "pack set layout");
            packSetLayout = p.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            // A set per frame for the scene, and one for the shadow map: its commands run first.
            int sets = frames * 2;
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(sets);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(sets * 6);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(sets).pPoolSizes(sizes), null, p), "pack pool");
            packPool = p.get(0);
            LongBuffer layouts = stack.mallocLong(sets);
            for (int i = 0; i < sets; i++) layouts.put(i, packSetLayout);
            LongBuffer out = stack.mallocLong(sets);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(packPool).pSetLayouts(layouts), out), "pack sets");
            packSets = new long[sets];
            packUbo = new long[sets];
            packUboAlloc = new long[sets];
            packUboPtr = new long[sets];
            for (int i = 0; i < sets; i++) {
                packSets[i] = out.get(i);
                long[] r = hostBuffer(stack, PACK_UBO_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                packUbo[i] = r[0];
                packUboAlloc[i] = r[1];
                packUboPtr[i] = r[2];
                long[] bufs = {packUbo[i], arena, sectionsBuf, modelsBuf, tintsBuf, coverBuf[i % frames], packIdsBuf};
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(7, stack);
                for (int k = 0; k < 7; k++) {
                    VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack).buffer(bufs[k]).offset(0).range(VK_WHOLE_SIZE);
                    w.get(k).sType$Default().dstSet(packSets[i]).dstBinding(k).descriptorCount(1).pBufferInfo(bi)
                            .descriptorType(k == 0 ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                }
                vkUpdateDescriptorSets(device, w, null);
            }
        }
    }

    /** The pipelines of the pack's dh_terrain and dh_water, rebuilt with the pack runtime; false without dh_terrain. */
    private boolean packPipelines() {
        Object token = net.vesu.vulkan.LodPackBridge.token();
        if (token == packToken) return packOpaque != 0;
        destroyPackPipeline();
        packToken = token;
        ensurePackSets();
        // Another pack, other block.properties ids.
        for (int m = 0; m < modelCount; m++) {
            MemoryUtil.memPutInt(packIdsPtr + m * 4L, net.vesu.shader.PackIds.block(MemoryUtil.memGetInt(modelsPtr + m * 64L + 32)));
        }
        net.vesu.vulkan.LodPackProgram prog = net.vesu.vulkan.LodPackBridge.program(false);
        net.vesu.vulkan.LodPackProgram shadowProg = net.vesu.vulkan.LodPackBridge.shadowProgram();
        if (prog == null && shadowProg == null) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            long res = net.vesu.vulkan.LodPackBridge.resourceSetLayout();
            LongBuffer setLayouts = res != 0 ? stack.longs(net.vesu.vulkan.LodPackBridge.sharedSetLayout(), packSetLayout, res)
                    : stack.longs(net.vesu.vulkan.LodPackBridge.sharedSetLayout(), packSetLayout);
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS).offset(0).size(net.vesu.vulkan.Drawer.DRAW_BLOCK_SIZE);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(setLayouts).pPushConstantRanges(pcr), null, p), "pack pipeline layout");
            packLayout = p.get(0);
            int[] formats = net.vesu.vulkan.LodPackBridge.colorFormats();
            int depth = net.vesu.vulkan.LodPackBridge.DEPTH_FORMAT;
            if (prog != null) packOpaque = packPipeline(stack, prog, false, "dh_terrain", formats, depth);
            net.vesu.vulkan.LodPackProgram water = net.vesu.vulkan.LodPackBridge.program(true);
            if (packOpaque != 0 && water != null) packTranslucent = packPipeline(stack, water, true, "dh_water", formats, depth);
            if (shadowProg != null) {
                packShadow = packPipeline(stack, shadowProg, false, "shadow", net.vesu.vulkan.LodPackBridge.shadowColorFormats(),
                        net.vesu.vulkan.LodPackBridge.shadowDepthFormat());
            }
        }
        return packOpaque != 0;
    }

    /** Vanilla's translucent layer: alpha blending, alpha kept from the source. */
    private static final net.vesu.vulkan.Pipeline.BlendState WATER_BLEND = new net.vesu.vulkan.Pipeline.BlendState(770, 771, 1, 0);

    private long packPipeline(MemoryStack stack, net.vesu.vulkan.LodPackProgram prog, boolean translucent, String name,
                              int[] formats, int depthFormat) {
        LongBuffer p = stack.mallocLong(1);
        long vm = module(stack, prog.vertex), fm = module(stack, prog.fragment);
        VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(formats.length, stack);
        for (int i = 0; i < formats.length; i++) {
            net.vesu.vulkan.Pipeline.BlendState bs = prog.targetBlend != null && prog.targetBlend[i] != null ? prog.targetBlend[i] : prog.blend != null ? prog.blend : translucent ? WATER_BLEND : null;
            VkPipelineColorBlendAttachmentState a = blend.get(i).colorWriteMask((prog.targetMask & 1 << i) != 0 ? 0xF : 0);
            if (bs != null && bs.enabled) {
                a.blendEnable(true).srcColorBlendFactor(bs.srcRgbFactor).dstColorBlendFactor(bs.dstRgbFactor).colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(bs.srcAlphaFactor).dstAlphaBlendFactor(bs.dstAlphaFactor).alphaBlendOp(VK_BLEND_OP_ADD);
            }
        }
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(stack.UTF8("main"));
        VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        info.get(0).sType$Default()
                .pStages(stages)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                        .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE)
                        .lineWidth(1.0f))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                        .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                        .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                .layout(packLayout)
                .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                        .colorAttachmentCount(formats.length).pColorAttachmentFormats(stack.ints(formats))
                        .depthAttachmentFormat(depthFormat)
                        .stencilAttachmentFormat(VK_FORMAT_UNDEFINED).address());
        int rc = vkCreateGraphicsPipelines(device, Pipeline.getPipelineCache(), info, null, p);
        vkDestroyShaderModule(device, vm, null);
        vkDestroyShaderModule(device, fm, null);
        if (rc != VK_SUCCESS) {
            Initializer.LOGGER.warn("[lod] the pack's {} pipeline failed: {}", name, rc);
            return 0;
        }
        return p.get(0);
    }

    private void destroyPackPipeline() {
        long pl = packOpaque, tl = packTranslucent, sl = packShadow, lay = packLayout;
        packOpaque = packTranslucent = packShadow = packLayout = 0;
        packToken = null;
        if (pl == 0 && tl == 0 && sl == 0 && lay == 0) return;
        MemoryManager.addToFreeable(() -> {
            if (pl != 0) vkDestroyPipeline(device, pl, null);
            if (sl != 0) vkDestroyPipeline(device, sl, null);
            if (tl != 0) vkDestroyPipeline(device, tl, null);
            if (lay != 0) vkDestroyPipelineLayout(device, lay, null);
        });
    }

    /**
     * Under a pack: draws this frame's opaque LOD commands through the pack's dh_terrain into
     * the pack's targets, with the distant terrain's own depth (dhDepthTex0). {@code mvp} is
     * the depth-remapped DH projection times {@code modelView}; positions are camera-relative.
     * False when the pack's programs cannot draw: no distant water either.
     */
    public boolean drawPack(org.joml.Matrix4f mvp, org.joml.Matrix4f modelView, int camBX, int camBY, int camBZ,
                            float fx, float fy, float fz, org.joml.Matrix4f invMvp, int[] coverBits, int coverX, int coverZ,
                            int coverN, boolean flipY, boolean dropCovered) {
        if (!packPipelines()) return false;
        int words = (coverN * coverN * 16 + 31) / 32;
        for (int i = 0; i < words; i++) MemoryUtil.memPutInt(coverPtr[frame] + i * 4L, coverBits[i]);
        ByteBuffer u = MemoryUtil.memByteBuffer(packUboPtr[frame], PACK_UBO_SIZE);
        mvp.get(0, u);
        modelView.get(64, u);
        u.putInt(128, camBX).putInt(132, camBY).putInt(136, camBZ).putInt(140, 0);
        u.putFloat(144, fx).putFloat(148, fy).putFloat(152, fz).putFloat(156, 0);
        invMvp.get(160, u);
        u.putInt(224, coverX).putInt(228, coverN).putInt(232, coverZ).putInt(236, 1);
        u.putFloat(240, FrameTargets.scene.width()).putFloat(244, FrameTargets.scene.height()).putFloat(248, flipY ? -1 : 1)
                .putFloat(252, dropCovered ? 1 : 0);
        if (opaqueDraws > 0) drawPackPass(packOpaque, false, 0, opaqueDraws, 8);
        return true;
    }

    /**
     * Under a pack, after drawPack and the DEFERRED passes, before vanilla's translucent layer:
     * the translucent LOD commands, far to near, through dh_water. Runs without draws too, so
     * dhDepthTex1 is taken every frame.
     */
    public void drawPackTranslucent() {
        if (packTranslucent == 0) return;
        int n = writeTranslucent();
        drawPackPass(packTranslucent, true, (long) MAX_DRAWS * CMD, n, 17);
    }

    /**
     * Records this frame's shadow draws (levels 3 and 4) into the pack's shadow map, through its
     * shadow program. {@code mvp} is the light's remapped projection times its view; fragments
     * within {@code clipRadius} blocks of the camera are dropped, vanilla terrain is there.
     */
    public void drawPackShadow(org.joml.Matrix4f mvp, org.joml.Matrix4f modelView, int camBX, int camBY, int camBZ,
                               float fx, float fy, float fz, org.joml.Matrix4f invMvp, int size, int clipRadius) {
        packPipelines();
        VkCommandBuffer cmd = net.vesu.vulkan.LodPackBridge.shadowCommands();
        if (packShadow == 0 || shadowDraws == 0 || cmd == null) return;
        int set = frames + frame;
        ByteBuffer u = MemoryUtil.memByteBuffer(packUboPtr[set], PACK_UBO_SIZE);
        mvp.get(0, u);
        modelView.get(64, u);
        u.putInt(128, camBX).putInt(132, camBY).putInt(136, camBZ).putInt(140, 0);
        u.putFloat(144, fx).putFloat(148, fy).putFloat(152, fz).putFloat(156, 0);
        invMvp.get(160, u);
        u.putInt(224, 0).putInt(228, clipRadius).putInt(232, 0).putInt(236, 2);
        u.putFloat(240, size).putFloat(244, size).putFloat(248, 1).putFloat(252, 1);
        drawBlock.putFloat(0, 1).putFloat(4, 1).putFloat(8, 1).putFloat(12, 1);
        drawBlock.putInt(48, 8);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.x(0.0f).y(0.0f).width(size).height(size).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.extent().set(size, size);
            vkCmdSetScissor(cmd, 0, scissor);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, packShadow);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, packLayout, 0,
                    stack.longs(net.vesu.vulkan.LodPackBridge.shadowSet(), packSets[set]), null);
            net.vesu.vulkan.LodPackBridge.bindResources(cmd, packLayout);
            vkCmdPushConstants(cmd, packLayout, VK_SHADER_STAGE_ALL_GRAPHICS, 0, drawBlock);
            drawIndirect(cmd, SHADOW_OFFSET, shadowDraws);
        }
        Drawer.forgetBoundPipeline(cmd);
    }

    private void drawPackPass(long pipeline, boolean translucent, long offset, int count, int renderStage) {
        drawBlock.putFloat(0, 1).putFloat(4, 1).putFloat(8, 1).putFloat(12, 1);
        drawBlock.putInt(48, renderStage);
        VkCommandBuffer cmd = Drawer.currentCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            net.vesu.vulkan.LodPackBridge.begin(cmd, stack, translucent);
            if (count > 0) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, packLayout, 0,
                        stack.longs(net.vesu.vulkan.LodPackBridge.sharedSet(), packSets[frame]), null);
                net.vesu.vulkan.LodPackBridge.bindResources(cmd, packLayout);
                vkCmdPushConstants(cmd, packLayout, VK_SHADER_STAGE_ALL_GRAPHICS, 0, drawBlock);
                drawIndirect(cmd, offset, count);
            }
            net.vesu.vulkan.LodPackBridge.end(cmd, stack);
        }
        Drawer.forgetBoundPipeline(cmd);
    }

    private LodSsao ssao;

    public LodSsao ssao() {
        if (ssao == null) ssao = new LodSsao();
        return ssao;
    }

    /** SSAO over this frame's LOD pixels (AUDIT 5.15); inside scene rendering, after the opaque LODs. */
    public void recordSsao(org.joml.Matrix4f viewProj, int samples) {
        ssao().record(Drawer.currentCommandBuffer(), frame, uboBuf[frame], coverBuf[frame], viewProj, samples);
    }

    public int candidates() {
        return opaqueCandidates + translucentCandidates;
    }

    public void destroy() {
        if (traversal != null) traversal.destroy();
        traversal = null;
        if (ssao != null) ssao.destroy();
        destroyPackPipeline();
        if (packSetLayout != 0) {
            long sl = packSetLayout, pp = packPool;
            long[] ub = packUbo.clone(), ua = packUboAlloc.clone();
            MemoryManager.addToFreeable(() -> {
                vkDestroyDescriptorPool(device, pp, null);
                vkDestroyDescriptorSetLayout(device, sl, null);
                for (int i = 0; i < ub.length; i++) MemoryManager.freeBuffer(ub[i], ua[i]);
            });
        }
        long[] all = {arena, arenaAlloc, sectionsBuf, sectionsAlloc, modelsBuf, modelsAlloc, tintsBuf, tintsAlloc, packIdsBuf, packIdsAlloc};
        // The image defers its own destruction; queued tasks must not queue more tasks.
        atlas.free();
        destroyHiz();
        net.vesu.vulkan.ComputePipeline cp = cullPipeline, hp = hizPipeline;
        long hl = hizLayout, hpool = hizPool, hs = hizSampler, mb = metaBuf, ma = metaAlloc;
        long[] cb = candBuf.clone(), ca = candAlloc.clone(), gb = gpuCmdBuf.clone(), ga = gpuCmdAlloc.clone(),
                kb = countBuf.clone(), ka = countAlloc.clone();
        MemoryManager.addToFreeable(() -> {
            if (cp != null) cp.cleanUp();
            if (hp != null) hp.cleanUp();
            if (hpool != 0) vkDestroyDescriptorPool(device, hpool, null);
            if (hl != 0) vkDestroyDescriptorSetLayout(device, hl, null);
            if (hs != 0) vkDestroySampler(device, hs, null);
            MemoryManager.freeBuffer(mb, ma);
            for (int i = 0; i < cb.length; i++) {
                MemoryManager.freeBuffer(cb[i], ca[i]);
                MemoryManager.freeBuffer(gb[i], ga[i]);
                MemoryManager.freeBuffer(kb[i], ka[i]);
            }
        });
        MemoryManager.addToFreeable(() -> {
            destroyPipelines();
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorPool(device, pool, null);
            vkDestroyDescriptorSetLayout(device, setLayout, null);
            vkDestroySampler(device, sampler, null);
            for (int i = 0; i < all.length; i += 2) MemoryManager.freeBuffer(all[i], all[i + 1]);
            for (int i = 0; i < frames; i++) {
                MemoryManager.freeBuffer(uboBuf[i], uboAlloc[i]);
                MemoryManager.freeBuffer(cmdBuf[i], cmdAlloc[i]);
                MemoryManager.freeBuffer(coverBuf[i], coverAlloc[i]);
                MemoryManager.freeBuffer(stagingBuf[i], stagingAlloc[i]);
            }
        });
    }

    private static void check(int rc, String what) {
        if (rc != VK_SUCCESS) throw new IllegalStateException("LOD " + what + ": " + rc);
    }

    static int faceTileSize() {
        return FaceBaker.RES;
    }
}
