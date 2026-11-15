package net.vesu.vulkan;

import net.vesu.Env;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * HDR post chain of the ray-traced frame (docs/rt-production-look.md section 3), on the
 * RGBA16F scene before the upscaler: a downsample chain (post_down.comp: thresholded bloom
 * source, log luminance), auto-exposure (post_exposure.comp) and the tonemap
 * (post_tonemap.comp: bloom, local adaptation, exposure bias, ACES), written back into the
 * scene in display range. Only without a shader pack (packs grade their own image).
 * VESU_POST=0 leaves the scene as is (values above 1 then clip).
 */
final class PostProcess {

    static final boolean ENABLED = !"0".equals(Env.get("VESU_POST"));
    private static final int LEVELS = 6;
    private static final int SLOTS = RtDescriptors.SLOTS;

    // The post settings of NVIDIA's RTX Remix for Minecraft (packaging/rtx.conf and the time
    // profiles), in scene units (physical radiance times RtLighting.SCENE_SCALE).
    /** Exposure bias. */
    private static final float EXPOSURE_BIAS = 1.0f;
    /**
     * Bloom: by day only the hottest emitters (pre-tonemap radiance 250, broad and faint: 0.1),
     * at night every emitter (threshold 1, intensity 1.5); blended with the daylight.
     */
    private static final float BLOOM_THRESHOLD_DAY = 25.0f, BLOOM_THRESHOLD_NIGHT = 0.1f, BLOOM_KNEE = 0.5f;
    private static final float BLOOM_DAY = 0.1f, BLOOM_NIGHT = 1.5f;
    /**
     * Auto-exposure: key value and a 4 EV window (log2 of the exposure), whose bottom rises 2 EV
     * at dusk and night: a bright evening sky does not expose the scene down (Remix's dusk and
     * night profiles); fast adaptation.
     */
    private static final float KEY = 0.18f, EV_MIN_DAY = -2.0f, EV_MIN_NIGHT = 0.0f, EV_MAX = 2.0f, ADAPT_SPEED = 3.0f;
    /** Local tonemapping: shadows 1.4, highlights 2.5. */
    private static final float LOCAL_SHADOWS = 1.4f, LOCAL_HIGHLIGHTS = 2.5f, LOCAL_STRENGTH = 1.0f;

    private static final StorageImage[] levels = new StorageImage[LEVELS];
    private static final StorageImage exposure = new StorageImage(VK_FORMAT_R32G32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT);
    static {
        for (int i = 0; i < LEVELS; i++) {
            levels[i] = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        }
    }

    private static ComputePipeline down, expo, tone;
    private static long downLayout, expoLayout, toneLayout, pool;
    private static final long[][] downSets = new long[SLOTS][LEVELS];
    private static final long[] expoSets = new long[SLOTS], toneSets = new long[SLOTS];
    private static boolean resetExposure = true;
    private static long lastNanos;

    private PostProcess() {}

    static void record(VkCommandBuffer cmd, MemoryStack stack) {
        prepare();
        RenderTarget scene = FrameTargets.scene;
        int w = scene.width(), h = scene.height();
        java.util.ArrayList<StorageImage> created = new java.util.ArrayList<>();
        int lw = w, lh = h;
        for (int i = 0; i < LEVELS; i++) {
            lw = Math.max(1, (lw + 1) / 2);
            lh = Math.max(1, (lh + 1) / 2);
            if (levels[i].ensure(lw, lh)) created.add(levels[i]);
        }
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
        // The previous frame's reads of the levels before this frame's writes.
        barrier(cmd, stack);

        int slot = Drawer.getCurrentFrame() % SLOTS;
        float daylight = RtLighting.hasSky() ? RtLighting.sun()[3] : 1.0f;
        float bloomThreshold = (float) Math.exp(lerp((float) Math.log(BLOOM_THRESHOLD_NIGHT),
                (float) Math.log(BLOOM_THRESHOLD_DAY), daylight));
        float bloom = lerp(BLOOM_NIGHT, BLOOM_DAY, daylight);
        float evMin = lerp(EV_MIN_NIGHT, EV_MIN_DAY, daylight);
        // Downsample chain.
        down.bind(cmd);
        for (int i = 0; i < LEVELS; i++) {
            long set = downSets[slot][i];
            writeStorage(set, 0, i == 0 ? scene.colorView() : levels[i - 1].view, stack);
            writeStorage(set, 1, levels[i].view, stack);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, down.layout(), 0, stack.longs(set), null);
            ByteBuffer pc = stack.calloc(16);
            pc.putFloat(0, i == 0 ? 1.0f : 0.0f).putFloat(4, bloomThreshold).putFloat(8, BLOOM_KNEE * bloomThreshold);
            vkCmdPushConstants(cmd, down.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            down.dispatch(cmd, (levels[i].width + 7) / 8, (levels[i].height + 7) / 8, 1);
            barrier(cmd, stack);
        }

        // Auto-exposure from the coarsest level.
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.016f : Math.min(0.25f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        long es = expoSets[slot];
        writeStorage(es, 0, levels[LEVELS - 1].view, stack);
        writeStorage(es, 1, exposure.view, stack);
        expo.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, expo.layout(), 0, stack.longs(es), null);
        ByteBuffer pe = stack.calloc(32);
        pe.putFloat(0, KEY).putFloat(4, evMin).putFloat(8, EV_MAX)
                .putFloat(12, 1.0f - (float) Math.exp(-dt * ADAPT_SPEED)).putFloat(16, resetExposure ? 1.0f : 0.0f);
        vkCmdPushConstants(cmd, expo.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pe);
        expo.dispatch(cmd, 1, 1, 1);
        resetExposure = false;
        barrier(cmd, stack);

        // Tonemap in place.
        long ts = toneSets[slot];
        writeStorage(ts, 0, scene.colorView(), stack);
        for (int i = 1; i <= 5; i++) writeSampler(ts, i, levels[i].view, stack);
        writeStorage(ts, 6, exposure.view, stack);
        tone.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, tone.layout(), 0, stack.longs(ts), null);
        ByteBuffer pt = stack.calloc(32);
        pt.putFloat(0, EXPOSURE_BIAS).putFloat(4, bloom).putFloat(8, LOCAL_SHADOWS).putFloat(12, LOCAL_HIGHLIGHTS)
                .putFloat(16, LOCAL_STRENGTH).putFloat(20, 0.0f);
        vkCmdPushConstants(cmd, tone.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pt);
        tone.dispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
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
        if (down != null) return;
        int s = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, c = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        downLayout = layout(new int[]{s, s});
        expoLayout = layout(new int[]{s, s});
        toneLayout = layout(new int[]{s, c, c, c, c, c, s});
        try (MemoryStack stack = stackPush()) {
            int sets = SLOTS * (LEVELS + 2);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(s).descriptorCount(SLOTS * (LEVELS * 2 + 2 + 2));
            sizes.get(1).type(c).descriptorCount(SLOTS * 5);
            LongBuffer p = stack.mallocLong(1);
            RtPipeline.check(vkCreateDescriptorPool(Vulkan.getDevice(), VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(sets).pPoolSizes(sizes), null, p), "post pool");
            pool = p.get(0);
            for (int i = 0; i < SLOTS; i++) {
                for (int l = 0; l < LEVELS; l++) downSets[i][l] = allocate(downLayout, stack);
                expoSets[i] = allocate(expoLayout, stack);
                toneSets[i] = allocate(toneLayout, stack);
            }
        }
        down = compute("post_down.comp", downLayout, 16);
        expo = compute("post_exposure.comp", expoLayout, 32);
        tone = compute("post_tonemap.comp", toneLayout, 32);
    }

    private static long layout(int[] types) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer p = stack.mallocLong(1);
            RtPipeline.check(vkCreateDescriptorSetLayout(Vulkan.getDevice(), VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, p), "post set layout");
            return p.get(0);
        }
    }

    private static long allocate(long layout, MemoryStack stack) {
        LongBuffer out = stack.mallocLong(1);
        RtPipeline.check(vkAllocateDescriptorSets(Vulkan.getDevice(), VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(layout)), out), "post set");
        return out.get(0);
    }

    private static ComputePipeline compute(String file, long layout, int push) {
        ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile(file, ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        if (spv == null) throw new RuntimeException("post shader " + file + " failed to compile");
        ComputePipeline p = new ComputePipeline(spv, new long[]{layout}, push);
        spv.free();
        return p;
    }

    /** The device must be idle. */
    static void destroy() {
        for (StorageImage img : levels) img.destroy();
        exposure.destroy();
        resetExposure = true;
        if (down != null) {
            down.cleanUp();
            expo.cleanUp();
            tone.cleanUp();
            down = expo = tone = null;
            vkDestroyDescriptorPool(Vulkan.getDevice(), pool, null);
            for (long l : new long[]{downLayout, expoLayout, toneLayout}) vkDestroyDescriptorSetLayout(Vulkan.getDevice(), l, null);
            pool = downLayout = expoLayout = toneLayout = 0L;
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
