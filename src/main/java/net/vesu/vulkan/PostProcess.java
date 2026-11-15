package net.vesu.vulkan;

import net.vesu.Env;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * HDR post chain of the ray-traced frame: a port of NVIDIA RTX Remix's (dxvk-remix,
 * RtxContext::dispatchBloom / dispatchToneMapping) with the settings NVIDIA's RTX Remix for
 * Minecraft ships (packaging/rtx.conf), on the RGBA16F scene before the upscaler:
 * <ol>
 *   <li>rx_linear: the gamma-encoded scene as linear radiance in Remix's units;</li>
 *   <li>bloom (rtx_bloom.cpp): 13-tap downsample chain, the first step thresholded, tent
 *       upsampling, composite;</li>
 *   <li>auto-exposure (rtx_auto_exposure.cpp): EV100 histogram, center metering, mean mapped to
 *       middle grey, adapted over time;</li>
 *   <li>local tonemapping (rtx_local_tone_mapping.cpp): exposure fusion of three synthetic
 *       exposures over a Laplacian pyramid, guided upsampling, ACES, written back in display
 *       range.</li>
 * </ol>
 * Only without a shader pack (packs grade their own image). VESU_POST=0 leaves the scene as is.
 */
final class PostProcess {

    static final boolean ENABLED = !"0".equals(Env.get("VESU_POST"));
    private static final int SLOTS = RtDescriptors.SLOTS;

    // rtx.conf of RTX Remix for Minecraft (unset keys: dxvk-remix defaults).
    /** rtx.bloom.steps, burnIntensity (x0.01 in DxvkBloom::dispatchComposite), luminanceThreshold. */
    private static final int BLOOM_STEPS = 8;
    private static final float BLOOM_INTENSITY = 0.01f * 0.1f, BLOOM_THRESHOLD = 250.0f;
    /** rtx.autoExposure: evMinValue, evMaxValue, autoExposureSpeed (per second), exposureAverageMode (0 mean), center metering. */
    private static final float EV_MIN = -2.5f, EV_MAX = 1.5f, EXPOSURE_SPEED = 10.0f, CENTER_METERING_SIZE = 0.75f;
    private static final int AVERAGE_MODE = 0;
    /** rtx.localtonemap: exposure, shadows, highlights, exposurePreferenceSigma/Offset, mip (default 3), finalizeWithACES. */
    private static final float LT_EXPOSURE = 0.21405728f, LT_SHADOWS = 1.4f, LT_HIGHLIGHTS = 2.5f;
    private static final float LT_SIGMA = 3.5f, LT_OFFSET = -0.1f;
    private static final int LT_MIP = 3;
    /**
     * Scene units to Remix's radiance units (RtLighting.SCENE_SCALE: scene = Remix x 0.1).
     * VESU_POST_UNIT overrides it.
     */
    private static final float UNIT_SCALE = envFloat("VESU_POST_UNIT", 1.0f / RtLighting.SCENE_SCALE);

    private static final int STORAGE_SAMPLED = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    private static final StorageImage hdr = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, STORAGE_SAMPLED);
    private static final StorageImage[] bloom = new StorageImage[BLOOM_STEPS];
    private static final StorageImage[] lum = new StorageImage[LT_MIP + 1], wts = new StorageImage[LT_MIP + 1],
            asm = new StorageImage[LT_MIP + 1];
    private static final StorageImage exposure = new StorageImage(VK_FORMAT_R32G32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
    private static final StorageImage histogram = new StorageImage(VK_FORMAT_R32_UINT, VK_IMAGE_USAGE_STORAGE_BIT);
    static {
        for (int i = 0; i < BLOOM_STEPS; i++) bloom[i] = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, STORAGE_SAMPLED);
        for (int i = 0; i <= LT_MIP; i++) {
            lum[i] = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, STORAGE_SAMPLED);
            wts[i] = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, STORAGE_SAMPLED);
            asm[i] = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, STORAGE_SAMPLED);
        }
    }

    /** Descriptor sets per frame slot: one per dispatch. */
    private static final int SETS = 48;
    private static ComputePipeline linear, bloomDown, bloomUp, bloomComposite, hist, expo, ltLum, ltWeight, ltMip,
            ltBlend, ltLaplacian, ltFinal;
    private static long setLayout, pool;
    private static final long[][] sets = new long[SLOTS][SETS];
    private static int nextSet;
    private static boolean resetExposure = true;
    private static long lastNanos;

    private PostProcess() {}

    static void record(VkCommandBuffer cmd, MemoryStack stack) {
        prepare();
        RenderTarget scene = FrameTargets.scene;
        int w = scene.width(), h = scene.height();
        ArrayList<StorageImage> created = new ArrayList<>();
        if (hdr.ensure(w, h)) created.add(hdr);
        for (int i = 0; i < BLOOM_STEPS; i++) {
            int d = 1 << (i + 1);
            if (bloom[i].ensure((w + d - 1) / d, (h + d - 1) / d)) created.add(bloom[i]);
        }
        for (int i = 0; i <= LT_MIP; i++) {
            int lw = Math.max(1, w >> i), lh = Math.max(1, h >> i);
            if (lum[i].ensure(lw, lh)) created.add(lum[i]);
            if (wts[i].ensure(lw, lh)) created.add(wts[i]);
            if (asm[i].ensure(lw, lh)) created.add(asm[i]);
        }
        boolean freshHistogram = histogram.ensure(256, 1);
        if (freshHistogram) created.add(histogram);
        if (exposure.ensure(1, 1)) {
            created.add(exposure);
            resetExposure = true;
        }
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(created.size() + 1, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        for (int i = 0; i < created.size(); i++) {
            FrameTargets.imageBarrier(pre.get(i + 1), created.get(i).image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        }
        FrameTargets.pipelineBarrier(cmd, pre, stack);
        barrier(cmd, stack);   // the previous frame's reads before this frame's writes

        int slot = Drawer.getCurrentFrame() % SLOTS;
        nextSet = 0;
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.016f : Math.min(0.25f, (now - lastNanos) / 1e9f);
        lastNanos = now;

        // Linear radiance in Remix's units.
        dispatch(cmd, slot, linear, hdr.view, new long[0], scene.colorView(),
                push(stack, 16).putFloat(0, UNIT_SCALE), groups(w), groups(h), stack);

        // Bloom.
        for (int i = 0; i < BLOOM_STEPS; i++) {
            StorageImage in = i == 0 ? hdr : bloom[i - 1], out = bloom[i];
            ByteBuffer pc = push(stack, 32).putFloat(0, 1.0f / in.width).putFloat(4, 1.0f / in.height)
                    .putFloat(8, 1.0f / out.width).putFloat(12, 1.0f / out.height)
                    .putInt(16, out.width).putInt(20, out.height).putFloat(24, i == 0 ? Math.max(0.01f, BLOOM_THRESHOLD) : -1.0f);
            dispatch(cmd, slot, bloomDown, out.view, new long[]{in.view}, 0L, pc, groups(out.width), groups(out.height), stack);
        }
        for (int i = BLOOM_STEPS - 1; i > 0; i--) {
            StorageImage in = bloom[i], out = bloom[i - 1];
            ByteBuffer pc = push(stack, 32).putFloat(0, 1.0f / in.width).putFloat(4, 1.0f / in.height)
                    .putFloat(8, 1.0f / out.width).putFloat(12, 1.0f / out.height).putInt(16, out.width).putInt(20, out.height);
            dispatch(cmd, slot, bloomUp, out.view, new long[]{in.view}, 0L, pc, groups(out.width), groups(out.height), stack);
        }
        dispatch(cmd, slot, bloomComposite, hdr.view, new long[]{bloom[0].view}, 0L,
                push(stack, 32).putFloat(0, 1.0f / w).putFloat(4, 1.0f / h).putInt(8, w).putInt(12, h).putFloat(16, BLOOM_INTENSITY),
                groups(w), groups(h), stack);

        // Auto-exposure (a fresh histogram holds garbage: one exposure pass clears it first).
        ByteBuffer pe = push(stack, 32).putFloat(0, EV_MIN).putFloat(4, EV_MAX - EV_MIN)
                .putFloat(8, 1.0f - (float) Math.pow(2.0, -EXPOSURE_SPEED * dt)).putInt(12, AVERAGE_MODE).putInt(16, 1);
        if (freshHistogram) dispatch(cmd, slot, expo, hdr.view, new long[0], 0L, pe, 1, 1, stack);
        dispatch(cmd, slot, hist, hdr.view, new long[]{hdr.view}, 0L,
                push(stack, 16).putFloat(0, EV_MIN).putFloat(4, EV_MAX - EV_MIN).putFloat(8, CENTER_METERING_SIZE).putInt(12, 1),
                groups(w), groups(h), stack);
        pe.putInt(16, resetExposure ? 1 : 0);
        dispatch(cmd, slot, expo, hdr.view, new long[0], 0L, pe, 1, 1, stack);
        resetExposure = false;

        // Local tonemapping.
        dispatch(cmd, slot, ltLum, lum[0].view, new long[]{hdr.view}, 0L,
                push(stack, 16).putFloat(0, LT_EXPOSURE).putFloat(4, (float) Math.pow(2.0, LT_SHADOWS))
                        .putFloat(8, (float) Math.pow(2.0, -LT_HIGHLIGHTS)), groups(w), groups(h), stack);
        dispatch(cmd, slot, ltWeight, wts[0].view, new long[]{lum[0].view}, 0L,
                push(stack, 16).putFloat(0, LT_SIGMA * LT_SIGMA).putFloat(4, LT_OFFSET), groups(w), groups(h), stack);
        for (int i = 1; i <= LT_MIP; i++) {
            dispatch(cmd, slot, ltMip, lum[i].view, new long[]{lum[i - 1].view}, 0L, push(stack, 16),
                    groups(lum[i].width), groups(lum[i].height), stack);
            dispatch(cmd, slot, ltMip, wts[i].view, new long[]{wts[i - 1].view}, 0L, push(stack, 16),
                    groups(wts[i].width), groups(wts[i].height), stack);
        }
        dispatch(cmd, slot, ltBlend, asm[LT_MIP].view, new long[]{lum[LT_MIP].view, wts[LT_MIP].view}, 0L, push(stack, 16),
                groups(asm[LT_MIP].width), groups(asm[LT_MIP].height), stack);
        for (int i = LT_MIP; i > 0; i--) {
            dispatch(cmd, slot, ltLaplacian, asm[i - 1].view,
                    new long[]{lum[i - 1].view, lum[i].view, wts[i - 1].view, asm[i].view}, 0L, push(stack, 16),
                    groups(asm[i - 1].width), groups(asm[i - 1].height), stack);
        }
        dispatch(cmd, slot, ltFinal, scene.colorView(), new long[]{asm[0].view, lum[0].view, hdr.view}, 0L,
                push(stack, 32).putFloat(0, w).putFloat(4, h).putFloat(8, 1.0f / w).putFloat(12, 1.0f / h)
                        .putFloat(16, LT_EXPOSURE).putInt(20, 1), groups(w), groups(h), stack);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    private static int groups(int n) { return (n + 15) / 16; }

    private static ByteBuffer push(MemoryStack stack, int size) { return stack.calloc(size); }

    /**
     * One pass: binding 0 its storage output, 1-4 the sampled inputs (unused ones get the HDR
     * image), 5 the exposure, 6 the histogram, 7 a storage input (the scene; else the HDR image).
     */
    private static void dispatch(VkCommandBuffer cmd, int slot, ComputePipeline p, long out, long[] inputs, long storageIn,
                                 ByteBuffer pc, int gx, int gy, MemoryStack stack) {
        long set = sets[slot][nextSet++];
        writeStorage(set, 0, out, stack);
        for (int i = 0; i < 4; i++) writeSampler(set, 1 + i, i < inputs.length ? inputs[i] : hdr.view, stack);
        writeStorage(set, 5, exposure.view, stack);
        writeStorage(set, 6, histogram.view, stack);
        writeStorage(set, 7, storageIn != 0L ? storageIn : hdr.view, stack);
        p.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout(), 0, stack.longs(set), null);
        vkCmdPushConstants(cmd, p.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
        p.dispatch(cmd, gx, gy, 1);
        barrier(cmd, stack);
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_SHADER_READ_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        VkCompat.pipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    private static void writeStorage(long set, int binding, long view, MemoryStack stack) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
        w.get(0).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
        vkUpdateDescriptorSets(Vulkan.getDevice(), w, null);
    }

    private static void writeSampler(long set, int binding, long view, MemoryStack stack) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(ComputeSets.linearClampSampler()).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
        w.get(0).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
        vkUpdateDescriptorSets(Vulkan.getDevice(), w, null);
    }

    private static void prepare() {
        if (linear != null) return;
        int s = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, c = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        int[] types = {s, c, c, c, c, s, s, s};
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer p = stack.mallocLong(1);
            RtPipeline.check(vkCreateDescriptorSetLayout(Vulkan.getDevice(), VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, p), "post set layout");
            setLayout = p.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(s).descriptorCount(SLOTS * SETS * 4);
            sizes.get(1).type(c).descriptorCount(SLOTS * SETS * 4);
            RtPipeline.check(vkCreateDescriptorPool(Vulkan.getDevice(), VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(SLOTS * SETS).pPoolSizes(sizes), null, p), "post pool");
            pool = p.get(0);
            for (int i = 0; i < SLOTS; i++) {
                for (int j = 0; j < SETS; j++) {
                    LongBuffer out = stack.mallocLong(1);
                    RtPipeline.check(vkAllocateDescriptorSets(Vulkan.getDevice(), VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), out), "post set");
                    sets[i][j] = out.get(0);
                }
            }
        }
        linear = compute("rx_linear.comp");
        bloomDown = compute("rx_bloom_down.comp");
        bloomUp = compute("rx_bloom_up.comp");
        bloomComposite = compute("rx_bloom_composite.comp");
        hist = compute("rx_histogram.comp");
        expo = compute("rx_exposure.comp");
        ltLum = compute("rx_lt_luminance.comp");
        ltWeight = compute("rx_lt_weight.comp");
        ltMip = compute("rx_lt_mip.comp");
        ltBlend = compute("rx_lt_blend.comp");
        ltLaplacian = compute("rx_lt_laplacian.comp");
        ltFinal = compute("rx_lt_final.comp");
    }

    private static ComputePipeline compute(String file) {
        ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile(file, ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        if (spv == null) throw new RuntimeException("post shader " + file + " failed to compile");
        ComputePipeline p = new ComputePipeline(spv, new long[]{setLayout}, 32);
        spv.free();
        return p;
    }

    private static float envFloat(String name, float fallback) {
        String v = Env.get(name);
        if (v == null) return fallback;
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The device must be idle. */
    static void destroy() {
        hdr.destroy();
        for (StorageImage img : bloom) img.destroy();
        for (int i = 0; i <= LT_MIP; i++) {
            lum[i].destroy();
            wts[i].destroy();
            asm[i].destroy();
        }
        exposure.destroy();
        histogram.destroy();
        resetExposure = true;
        if (linear != null) {
            for (ComputePipeline p : new ComputePipeline[]{linear, bloomDown, bloomUp, bloomComposite, hist, expo, ltLum,
                    ltWeight, ltMip, ltBlend, ltLaplacian, ltFinal}) {
                p.cleanUp();
            }
            linear = null;
            vkDestroyDescriptorPool(Vulkan.getDevice(), pool, null);
            vkDestroyDescriptorSetLayout(Vulkan.getDevice(), setLayout, null);
            pool = setLayout = 0L;
        }
    }
}
