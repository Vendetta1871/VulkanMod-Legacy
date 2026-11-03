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
    private final long[] uboBuf, uboAlloc, uboPtr, cmdBuf, cmdAlloc, cmdPtr, coverBuf, coverAlloc, coverPtr, stagingBuf, stagingAlloc, stagingPtr;

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
            r = hostBuffer(stack, (long) Bakery.TINT_STRIDE * Bakery.TINT_SLOTS * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            tintsBuf = r[0]; tintsAlloc = r[1]; tintsPtr = r[2];
        }
        uboBuf = new long[frames]; uboAlloc = new long[frames]; uboPtr = new long[frames];
        cmdBuf = new long[frames]; cmdAlloc = new long[frames]; cmdPtr = new long[frames];
        coverBuf = new long[frames]; coverAlloc = new long[frames]; coverPtr = new long[frames];
        stagingBuf = new long[frames]; stagingAlloc = new long[frames]; stagingPtr = new long[frames];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < frames; i++) {
                long[] r = hostBuffer(stack, UBO_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                uboBuf[i] = r[0]; uboAlloc[i] = r[1]; uboPtr[i] = r[2];
                r = hostBuffer(stack, (long) MAX_DRAWS * 16 * 2, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
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
        long p = cmdPtr[frame] + (long) opaqueDraws * 16;
        MemoryUtil.memPutInt(p, count * 6);
        MemoryUtil.memPutInt(p + 4, 1);
        MemoryUtil.memPutInt(p + 8, (int) (firstQuad * 6));
        MemoryUtil.memPutInt(p + 12, slot);
        opaqueDraws++;
    }

    /** Translucent draws are sorted far to near before drawing; {@code distance} in blocks. */
    public void addTranslucent(long firstQuad, int count, int slot, float distance) {
        if (count <= 0) return;
        if (translucentCount == translucentSort.length) {
            translucentSort = java.util.Arrays.copyOf(translucentSort, translucentCount * 2);
            translucentCmd = java.util.Arrays.copyOf(translucentCmd, translucentCount * 8);
        }
        int i = translucentCount++;
        translucentSort[i] = (long) (Integer.MAX_VALUE - Float.floatToIntBits(Math.max(0, distance))) << 32 | i;
        translucentCmd[i * 4] = count * 6;
        translucentCmd[i * 4 + 1] = (int) (firstQuad * 6);
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

    public float atlasWidth() {
        return ATLAS_W;
    }

    public float atlasHeight() {
        return atlasRows * 32;
    }

    private void writeSet(int i) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(8, stack);
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
            vkUpdateDescriptorSets(device, w, null);
        }
    }

    // ---- Pipelines ----

    private void createLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(8, stack);
            int vf = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
            int[] types = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
            for (int i = 0; i < 8; i++) b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(vf);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p), "set layout");
            setLayout = p.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(frames * 2);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(frames * 10);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(frames * 4);
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
        java.util.Arrays.sort(translucentSort, 0, translucentCount);
        long base = cmdPtr[frame] + (long) MAX_DRAWS * 16;
        int n = Math.min(translucentCount, MAX_DRAWS);
        for (int k = 0; k < n; k++) {
            int i = (int) translucentSort[k];
            long p = base + (long) k * 16;
            MemoryUtil.memPutInt(p, translucentCmd[i * 4]);
            MemoryUtil.memPutInt(p + 4, 1);
            MemoryUtil.memPutInt(p + 8, translucentCmd[i * 4 + 1]);
            MemoryUtil.memPutInt(p + 12, translucentCmd[i * 4 + 2]);
        }
        VkCommandBuffer cmd = Drawer.currentCommandBuffer();
        if (opaquePipeline == 0 || pipelineDepthFormat != FrameTargets.depthFormat()) createPipelines();
        bind(cmd, translucentPipeline);
        drawIndirect(cmd, (long) MAX_DRAWS * 16, n);
        Drawer.forgetBoundPipeline(cmd);
    }

    private void drawIndirect(VkCommandBuffer cmd, long offset, int count) {
        if (Vulkan.multiDrawIndirectSupported) {
            int max = maxDrawIndirect;
            for (int done = 0; done < count; done += max) {
                vkCmdDrawIndirect(cmd, cmdBuf[frame], offset + done * 16L, Math.min(max, count - done), 16);
            }
        } else {
            for (int i = 0; i < count; i++) vkCmdDrawIndirect(cmd, cmdBuf[frame], offset + i * 16L, 1, 16);
        }
    }

    public void destroy() {
        long[] all = {arena, arenaAlloc, sectionsBuf, sectionsAlloc, modelsBuf, modelsAlloc, tintsBuf, tintsAlloc};
        // The image defers its own destruction; queued tasks must not queue more tasks.
        atlas.free();
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
