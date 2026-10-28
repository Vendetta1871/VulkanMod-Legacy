package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.CameraHistory;
import net.vulkanmod.vulkan.rt.RtLights;
import net.vulkanmod.vulkan.rt.RtScene;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Hybrid ray-traced lighting on the raster scene (T8): sun/moon shadows, shadowed,
 * directional block light (torches, glowstone, lava...) and one-bounce indirect light. lighting.rgen traces and
 * accumulates the block light over frames; rt_composite.comp filters it and relights
 * the scene color in place, before translucent geometry and the upscaler.
 * Strengths: Config.rtShadowStrength, rtBlockLight, rtIndirect (VULKANMOD_RT_SHADOWS, _BLOCK, _GI).
 */
final class RtLightingPass {

    private static final int RT_PUSH = 208, COMPOSITE_PUSH = 80;
    /** VULKANMOD_DEBUG_VIEW=rtlight / rtgi: shows the filtered RT block light / indirect light alone. */
    private static final float DEBUG = "rtlight".equalsIgnoreCase(System.getenv("VULKANMOD_DEBUG_VIEW")) ? 1.0f
            : "rtgi".equalsIgnoreCase(System.getenv("VULKANMOD_DEBUG_VIEW")) ? 2.0f : 0.0f;
    /** Open sky gathers about the sky term; this scales the indirect correction. */
    private static final float GI_SCALE = 0.4f;

    private static RtPipeline rtPipeline;
    private static ComputePipeline composite;
    private static long setLayout, pool;
    private static final long[] sets = new long[RtDescriptors.SLOTS];

    private static final StorageImage[] history = {
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT)};
    private static final StorageImage aux = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
    private static final StorageImage normals = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT);
    private static final StorageImage[] giHistory = {
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT)};
    private static int parity, frame;
    private static boolean historyValid;

    private static final Matrix4f invJittered = new Matrix4f();
    private static final Matrix4f prevRel = new Matrix4f();

    private RtLightingPass() {}

    static void record(VkCommandBuffer cmd, MemoryStack stack) {
        if (!RtScene.ready() || !RtLights.ready() || net.vulkanmod.render.TextureBridge.getAtlasImage() == null) return;
        prepare();
        RenderTarget scene = FrameTargets.scene;
        int w = scene.width(), h = scene.height();
        boolean fresh = false;
        StorageImage[] all = {history[0], history[1], aux, normals, giHistory[0], giHistory[1]};
        for (StorageImage img : all) fresh |= img.ensure(w, h);
        if (fresh) historyValid = false;
        parity ^= 1;
        StorageImage cur = history[parity], prev = history[parity ^ 1];
        StorageImage giCur = giHistory[parity], giPrev = giHistory[parity ^ 1];

        // Inputs and the previous frame's readers/writers of these images.
        FrameTargets.sceneDepthToShaderRead(cmd, stack);
        if (fresh) {
            VkImageMemoryBarrier2.Buffer init = VkImageMemoryBarrier2.calloc(all.length, stack);
            for (int i = 0; i < all.length; i++) {
                FrameTargets.imageBarrier(init.get(i), all[i].image, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0,
                        VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                        VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            }
            FrameTargets.pipelineBarrier(cmd, init, stack);
        }
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

        long rtSet = RtDescriptors.write(RtDescriptors.PASS_LIGHTING, 0L, scene.depthView(), 0L,
                new long[]{prev.view, cur.view, aux.view, normals.view, giPrev.view, giCur.view});

        // The depth was rendered with the jittered projection.
        invJittered.identity()
                .m30(2.0f * CameraHistory.jitterX() / w)
                .m31(-2.0f * CameraHistory.jitterY() / h)
                .mul(CameraHistory.viewProj())
                .invert();
        double cx = CameraHistory.camX(), cy = CameraHistory.camY(), cz = CameraHistory.camZ();
        boolean hasPrev = CameraHistory.hasPrevious() && historyValid;
        prevRel.set(hasPrev ? CameraHistory.prevViewProj() : CameraHistory.viewProj());
        if (hasPrev) {
            prevRel.translate((float) (cx - CameraHistory.prevCamX()), (float) (cy - CameraHistory.prevCamY()),
                    (float) (cz - CameraHistory.prevCamZ()));
        }

        float[] sun = RtLighting.sun();
        boolean sky = RtLighting.hasSky();
        boolean day = sun[1] >= 0.0f;
        float sunStrength = !sky ? 0.0f : Initializer.CONFIG.effectiveRtShadowStrength() * (day ? 1.0f : 0.3f)
                * (1.0f - RtLighting.overcast())
                // Fade out while the sun or moon crosses the horizon.
                * Math.min(1.0f, Math.abs(sun[1]) / 0.1f);
        float s = day ? 1.0f : -1.0f;
        float[] camOff = RtLights.cameraOffset(cx, cy, cz);

        ByteBuffer c = stack.calloc(RT_PUSH);
        invJittered.get(0, c);
        prevRel.get(64, c);
        c.putFloat(128, sun[0] * s).putFloat(132, sun[1] * s).putFloat(136, sun[2] * s).putFloat(140, sunStrength);
        c.putFloat(144, Initializer.CONFIG.effectiveRtShadowDistance() * 16.0f).putFloat(148, frame++ & 0xFFFF)
                .putFloat(152, hasPrev ? 1.0f : 0.0f).putFloat(156, RtLights.gridMask());
        c.putFloat(160, camOff[0]).putFloat(164, camOff[1]).putFloat(168, camOff[2]).putFloat(172, RtLights.count());
        c.putFloat(176, (float) (CameraHistory.prevCamX() - cx)).putFloat(180, (float) (CameraHistory.prevCamY() - cy))
                .putFloat(184, (float) (CameraHistory.prevCamZ() - cz))
                .putFloat(188, sky ? 0.2f + 0.8f * sun[3] : 0.0f);
        float gi = Initializer.CONFIG.effectiveRtIndirect();
        float[] skyRad = skyRadiance();
        float giRange = gi > 0.0f ? Initializer.CONFIG.effectiveRtIndirectDistance() * 16.0f : 0.0f;
        c.putFloat(192, skyRad[0]).putFloat(196, skyRad[1]).putFloat(200, skyRad[2]).putFloat(204, giRange);
        rtPipeline.trace(cmd, rtSet, c, w, h);
        historyValid = true;

        // Composite: reads the RT outputs, writes the scene color.
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        long set = sets[Drawer.getCurrentFrame() % RtDescriptors.SLOTS];
        writeCompositeSet(set, scene, cur, giCur);
        ByteBuffer p = stack.calloc(COMPOSITE_PUSH);
        invJittered.get(0, p);
        p.putFloat(64, Initializer.CONFIG.effectiveRtBlockLight()).putFloat(68, 0.2f).putFloat(72, DEBUG)
                .putFloat(76, gi * GI_SCALE);
        composite.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, composite.layout(), 0, stack.longs(set), null);
        vkCmdPushConstants(cmd, composite.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, p);
        composite.dispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    private static void prepare() {
        if (rtPipeline == null) {
            rtPipeline = new RtPipeline(RtDescriptors.layout(), RT_PUSH, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/lighting.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit")});
            Initializer.LOGGER.info("[rt] lighting ready");
        }
        if (composite == null) {
            createSets();
            ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile("rt_composite.comp",
                    ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
            composite = new ComputePipeline(spv, new long[]{setLayout}, COMPOSITE_PUSH);
            spv.free();
        }
    }

    private static void createSets() {
        VkDevice device = Vulkan.getDevice();
        int[] types = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
        try (MemoryStack stack = stackPush()) {
            LongBuffer ptr = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                sizes.get(i).type(types[i]).descriptorCount(RtDescriptors.SLOTS);
            }
            RtPipeline.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, ptr), "RT composite set layout");
            setLayout = ptr.get(0);
            RtPipeline.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RtDescriptors.SLOTS).pPoolSizes(sizes), null, ptr), "RT composite pool");
            pool = ptr.get(0);
            LongBuffer layouts = stack.mallocLong(RtDescriptors.SLOTS);
            for (int i = 0; i < RtDescriptors.SLOTS; i++) layouts.put(i, setLayout);
            LongBuffer out = stack.mallocLong(RtDescriptors.SLOTS);
            RtPipeline.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "RT composite sets");
            for (int i = 0; i < RtDescriptors.SLOTS; i++) sets[i] = out.get(i);
        }
    }

    private static void writeCompositeSet(long set, RenderTarget scene, StorageImage cur, StorageImage giCur) {
        try (MemoryStack stack = stackPush()) {
            long[] views = {scene.depthView(), scene.colorView(), cur.view, aux.view, normals.view, giCur.view};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(views.length, stack);
            for (int i = 0; i < views.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                if (i == 0) {
                    info.get(0).sampler(ComputeSets.nearestClampSampler()).imageView(views[i])
                            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                } else {
                    info.get(0).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                }
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).pImageInfo(info)
                        .descriptorType(i == 0 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            }
            vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);
        }
    }

    /** Vanilla's sky color, half desaturated and normalized to luminance 1 (the sky term carries the level). */
    private static float[] skyRadiance() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        float r = 0.6f, g = 0.75f, b = 1.0f;
        if (mc.world != null && mc.getRenderViewEntity() != null) {
            net.minecraft.util.math.Vec3d v = mc.world.getSkyColor(mc.getRenderViewEntity(), mc.getRenderPartialTicks());
            r = (float) v.x; g = (float) v.y; b = (float) v.z;
        }
        float lum = Math.max(0.2126f * r + 0.7152f * g + 0.0722f * b, 1e-3f);
        r /= lum; g /= lum; b /= lum;
        return new float[]{0.5f + 0.5f * r, 0.5f + 0.5f * g, 0.5f + 0.5f * b};
    }

    private static void memoryBarrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess,
                                      long dstStage, long dstAccess) {
        VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    static void destroy() {
        for (StorageImage img : new StorageImage[]{history[0], history[1], aux, normals, giHistory[0], giHistory[1]}) {
            img.destroy();
        }
        historyValid = false;
        if (rtPipeline != null) { rtPipeline.destroy(); rtPipeline = null; }
        if (composite != null) {
            composite.cleanUp();
            composite = null;
            vkDestroyDescriptorPool(Vulkan.getDevice(), pool, null);
            vkDestroyDescriptorSetLayout(Vulkan.getDevice(), setLayout, null);
            pool = setLayout = 0L;
        }
    }
}
