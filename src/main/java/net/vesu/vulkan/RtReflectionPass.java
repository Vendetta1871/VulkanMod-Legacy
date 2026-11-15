package net.vesu.vulkan;

import net.minecraft.client.Minecraft;
import net.vesu.Initializer;
import net.vesu.render.CameraHistory;
import net.vesu.vulkan.rt.RtScene;
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
 * Ray-traced reflections (T8), see reflections.rgen. Recorded twice per frame: on smooth
 * opaque materials right after the RT lighting (before the opaque-only copy, so FSR does not
 * mark them reactive and accumulates the glossy jitter), and on water, glass and ice after
 * the translucent layer (with water refraction). Strength: Config.rtReflections
 * (VESU_RT_REFLECTIONS). With NRD the opaque pass traces glossy (GGX) reflections into an
 * image, RELAX_SPECULAR denoises them and rt_reflect_resolve.comp blends them into the scene.
 */
final class RtReflectionPass {

    private static final int PUSH = 256;
    private static RtPipeline pipeline;
    private static final Matrix4f invJittered = new Matrix4f();
    private static final Matrix4f prevRel = new Matrix4f();
    /** Reflection history of the opaque pass (ping-pong): color + frames, surface distance. */
    private static final StorageImage[] history = {
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT)};
    private static final StorageImage[] historyDist = {
            new StorageImage(VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            new StorageImage(VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT)};
    /** NRD path: traced reflections (radiance, hit distance), blend weights, denoised reflections. */
    private static final int NRD_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    private static final StorageImage specRaw = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, NRD_USAGE),
            specWeight = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            specOut = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, NRD_USAGE);
    private static ComputePipeline resolve;
    private static long resolveLayout, resolvePool;
    private static final long[] resolveSets = new long[RtDescriptors.SLOTS];
    private static int parity;
    private static boolean historyValid;
    private static final long START = System.nanoTime();

    private RtReflectionPass() {}

    /** @param translucent false: opaque materials (scene phase, depth readable); true: after the translucent layer */
    static void record(VkCommandBuffer cmd, MemoryStack stack, boolean translucent) {
        float strength = Initializer.CONFIG.effectiveRtReflections();
        int range = Initializer.CONFIG.effectiveRtReflectionDistance();
        if (strength <= 0.0f || range <= 0 || !RtScene.ready() || net.vesu.render.TextureBridge.getAtlasImage() == null) return;
        // No water, glass or ice in range: every primary ray of the translucent pass would miss.
        if (translucent && RtScene.translucentInstances() == 0) return;
        // Path traced: reflections (opaque and translucent) are part of the lighting's radiance.
        if (RtLightingPass.ptThisFrame) return;
        if (pipeline == null) {
            pipeline = new RtPipeline(RtDescriptors.layout(), PUSH, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/reflections.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit"),
                            new RtPipeline.HitGroup(null, "rt/shadow.rahit")});
            Initializer.LOGGER.info("[rt] reflections ready");
        }
        RenderTarget scene = FrameTargets.scene;
        boolean opaque = FrameTargets.opaqueTaken();
        // The opaque pass of each frame swaps the history; the translucent pass binds the same
        // images and leaves them alone.
        java.util.ArrayList<StorageImage> created = new java.util.ArrayList<>();
        for (StorageImage img : new StorageImage[]{history[0], history[1], historyDist[0], historyDist[1]}) {
            if (img.ensure(scene.width(), scene.height())) created.add(img);
        }
        // R1: the opaque pass skips rough terrain known from the lighting pass's normals.a.
        long normals = translucent ? 0L : RtLightingPass.normalsCovering(range * 16.0f);
        boolean nrdSpec = !translucent && normals != 0L && RtLightingPass.nrdThisFrame && NrdDenoiser.hasSpecular();
        if (nrdSpec) {
            for (StorageImage img : new StorageImage[]{specRaw, specWeight, specOut}) {
                if (img.ensure(scene.width(), scene.height())) created.add(img);
            }
        }
        if (!created.isEmpty()) {
            historyValid = false;
            VkImageMemoryBarrier2.Buffer init = VkImageMemoryBarrier2.calloc(created.size(), stack);
            for (int i = 0; i < created.size(); i++) {
                FrameTargets.imageBarrier(init.get(i), created.get(i).image, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                        VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            }
            FrameTargets.pipelineBarrier(cmd, init, stack);
        }
        if (!translucent) parity ^= 1;
        boolean hasPrev = !translucent && historyValid && CameraHistory.hasPrevious();

        FrameTargets.sceneDepthToShaderRead(cmd, stack);
        // The lighting pass wrote the normals from ray-tracing shaders.
        VkMemoryBarrier2.Buffer mem = VkMemoryBarrier2.calloc(1, stack);
        mem.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR).srcAccessMask(VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR).dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        VkCompat.pipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(mem));
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(opaque ? 2 : 1, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        if (opaque) {
            FrameTargets.imageBarrier(pre.get(1), FrameTargets.opaqueColor.image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_READ_BIT,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        }
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        long set = RtDescriptors.write(translucent ? RtDescriptors.PASS_REFLECTIONS : RtDescriptors.PASS_REFLECTIONS_OPAQUE,
                scene.colorView(), scene.depthView(),
                // Binding 5 must be valid even unused: the scene color stands in (same layout).
                opaque ? FrameTargets.opaqueColor.view : scene.colorView(),
                // Binding 12 likewise: without the lighting normals the flag below is 0.
                normals != 0L ? normals : scene.colorView());
        RtDescriptors.writeStorageImages(set, 29, history[parity ^ 1].view, history[parity].view,
                historyDist[parity ^ 1].view, historyDist[parity].view);
        if (nrdSpec) RtDescriptors.writeStorageImages(set, 35, specRaw.view, specWeight.view);

        invJittered.identity()
                .m30(2.0f * CameraHistory.jitterX() / scene.width())
                .m31(-2.0f * CameraHistory.jitterY() / scene.height())
                .mul(CameraHistory.viewProj())
                .invert();
        float[] sun = RtLighting.sun();
        boolean sky = RtLighting.hasSky();
        boolean day = sun[1] >= 0.0f;
        float glint = !sky ? 0.0f : (day ? 1.0f : 0.25f) * (1.0f - RtLighting.overcast()) * Math.min(1.0f, Math.abs(sun[1]) / 0.1f);
        float s = day ? 1.0f : -1.0f;
        float[] skyColor = skyColor();

        ByteBuffer c = stack.calloc(PUSH);
        invJittered.get(0, c);
        c.putFloat(64, sun[0] * s).putFloat(68, sun[1] * s).putFloat(72, sun[2] * s).putFloat(76, glint);
        c.putFloat(80, skyColor[0]).putFloat(84, skyColor[1]).putFloat(88, skyColor[2])
                .putFloat(92, (float) ((System.nanoTime() - START) / 1e9 % 3600.0));
        c.putFloat(96, strength).putFloat(100, opaque ? 1.0f : 0.0f).putFloat(104, range * 16.0f)
                .putFloat(108, sky ? 0.2f + 0.8f * sun[3] : 0.0f);
        c.putFloat(112, (float) (CameraHistory.camX() % 1024.0)).putFloat(116, (float) (CameraHistory.camY() % 1024.0))
                .putFloat(120, (float) (CameraHistory.camZ() % 1024.0)).putFloat(124, translucent ? 1.0f : 0.0f);
        c.putFloat(128, normals != 0L ? 1.0f : 0.0f).putFloat(132, hasPrev ? 1.0f : 0.0f)
                .putFloat(136, nrdSpec ? 1.0f : 0.0f);
        double cx = CameraHistory.camX(), cy = CameraHistory.camY(), cz = CameraHistory.camZ();
        prevRel.set(hasPrev ? CameraHistory.prevViewProj() : CameraHistory.viewProj());
        if (hasPrev) {
            prevRel.translate((float) (cx - CameraHistory.prevCamX()), (float) (cy - CameraHistory.prevCamY()),
                    (float) (cz - CameraHistory.prevCamZ()));
        }
        prevRel.get(144, c);
        c.putFloat(208, (float) (CameraHistory.prevCamX() - cx)).putFloat(212, (float) (CameraHistory.prevCamY() - cy))
                .putFloat(216, (float) (CameraHistory.prevCamZ() - cz));
        // Physical light rig (RtLighting, scene units): sky model inputs and the direct light.
        float[] mean = sky ? RtLighting.skyMean(sun) : new float[4];
        float[] direct = sky ? RtLighting.directLight(sun) : new float[3];
        c.putFloat(224, mean[0]).putFloat(228, mean[1]).putFloat(232, mean[2])
                .putFloat(236, sky ? RtLighting.skySource(sun) : 0.0f);
        c.putFloat(240, direct[0]).putFloat(244, direct[1]).putFloat(248, direct[2]);

        pipeline.trace(cmd, set, c, scene.width(), scene.height());
        if (!translucent) historyValid = true;
        if (nrdSpec) denoiseAndResolve(cmd, stack, scene);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(opaque ? 2 : 1, stack);
        FrameTargets.imageBarrier(post.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        if (opaque) {
            FrameTargets.imageBarrier(post.get(1), FrameTargets.opaqueColor.image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_READ_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    /** NRD path: RELAX_SPECULAR on the traced reflections, then the blend into the scene color. */
    private static void denoiseAndResolve(VkCommandBuffer cmd, MemoryStack stack, RenderTarget scene) {
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        long[][] common = RtLightingPass.nrdCommonInputs();
        long[][] images = new long[5][];
        images[NrdDenoiser.MV] = common[0];
        images[NrdDenoiser.NORMAL_ROUGHNESS] = common[1];
        images[NrdDenoiser.VIEWZ] = common[2];
        images[NrdDenoiser.BLOCK_IN] = new long[]{specRaw.image, specRaw.format, 0};
        images[NrdDenoiser.BLOCK_OUT] = new long[]{specOut.image, specOut.format, 0};
        NrdDenoiser.denoiseSpecular(cmd, images, stack);
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);

        if (resolve == null) createResolve();
        long rs = resolveSets[Drawer.getCurrentFrame() % RtDescriptors.SLOTS];
        RtDescriptors.writeStorageImages(rs, 0, scene.colorView(), specOut.view, specWeight.view);
        resolve.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, resolve.layout(), 0, stack.longs(rs), null);
        resolve.dispatch(cmd, (scene.width() + 7) / 8, (scene.height() + 7) / 8, 1);
        // The post barrier below waits for ray tracing: make the compute writes visible too.
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR);
    }

    private static void memoryBarrier(VkCommandBuffer cmd, MemoryStack stack, long src, long dst) {
        VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default().srcStageMask(src)
                .srcAccessMask(VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(dst).dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                        | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_SHADER_READ_BIT);
        VkCompat.pipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    private static void createResolve() {
        VkDevice device = Vulkan.getDevice();
        try (MemoryStack stack = stackPush()) {
            LongBuffer ptr = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(3, stack);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            for (int i = 0; i < 3; i++) {
                b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(3 * RtDescriptors.SLOTS);
            RtPipeline.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, ptr), "reflection resolve set layout");
            resolveLayout = ptr.get(0);
            RtPipeline.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RtDescriptors.SLOTS).pPoolSizes(sizes), null, ptr), "reflection resolve pool");
            resolvePool = ptr.get(0);
            LongBuffer layouts = stack.mallocLong(RtDescriptors.SLOTS);
            for (int i = 0; i < RtDescriptors.SLOTS; i++) layouts.put(i, resolveLayout);
            LongBuffer out = stack.mallocLong(RtDescriptors.SLOTS);
            RtPipeline.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(resolvePool).pSetLayouts(layouts), out), "reflection resolve sets");
            for (int i = 0; i < RtDescriptors.SLOTS; i++) resolveSets[i] = out.get(i);
        }
        ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile("rt_reflect_resolve.comp",
                ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        resolve = new ComputePipeline(spv, new long[]{resolveLayout}, 0);
        spv.free();
    }

    /** Vanilla's sky color at the camera (dark at night, grey in rain). */
    private static float[] skyColor() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.getRenderViewEntity() == null) return new float[]{0.5f, 0.7f, 1.0f};
        net.minecraft.util.math.Vec3d v = mc.world.getSkyColor(mc.getRenderViewEntity(), mc.getRenderPartialTicks());
        return new float[]{(float) v.x, (float) v.y, (float) v.z};
    }

    static void destroy() {
        for (StorageImage img : new StorageImage[]{history[0], history[1], historyDist[0], historyDist[1],
                specRaw, specWeight, specOut}) img.destroy();
        if (resolve != null) {
            resolve.cleanUp();
            resolve = null;
            vkDestroyDescriptorPool(Vulkan.getDevice(), resolvePool, null);
            vkDestroyDescriptorSetLayout(Vulkan.getDevice(), resolveLayout, null);
        }
        historyValid = false;
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
    }
}
