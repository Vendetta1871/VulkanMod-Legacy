package net.vulkanmod.vulkan;

import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.CameraHistory;
import net.vulkanmod.vulkan.rt.RtScene;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Ray-traced reflections (T8), see reflections.rgen. Recorded twice per frame: on smooth
 * opaque materials right after the RT lighting (before the opaque-only copy, so FSR does not
 * mark them reactive and accumulates the glossy jitter), and on water, glass and ice after
 * the translucent layer (with water refraction). Strength: Config.rtReflections
 * (VULKANMOD_RT_REFLECTIONS).
 */
final class RtReflectionPass {

    private static final int PUSH = 128;
    private static RtPipeline pipeline;
    private static final Matrix4f invJittered = new Matrix4f();
    private static final long START = System.nanoTime();

    private RtReflectionPass() {}

    /** @param translucent false: opaque materials (scene phase, depth readable); true: after the translucent layer */
    static void record(VkCommandBuffer cmd, MemoryStack stack, boolean translucent) {
        float strength = Initializer.CONFIG.effectiveRtReflections();
        int range = Initializer.CONFIG.effectiveRtReflectionDistance();
        if (strength <= 0.0f || range <= 0 || !RtScene.ready() || net.vulkanmod.render.TextureBridge.getAtlasImage() == null) return;
        if (pipeline == null) {
            pipeline = new RtPipeline(RtDescriptors.layout(), PUSH, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/reflections.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit"),
                            new RtPipeline.HitGroup(null, "rt/shadow.rahit")});
            Initializer.LOGGER.info("[rt] reflections ready");
        }
        RenderTarget scene = FrameTargets.scene;
        boolean opaque = FrameTargets.opaqueTaken();

        FrameTargets.sceneDepthToShaderRead(cmd, stack);
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
                opaque ? FrameTargets.opaqueColor.view : scene.colorView());

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

        pipeline.trace(cmd, set, c, scene.width(), scene.height());

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

    /** Vanilla's sky color at the camera (dark at night, grey in rain). */
    private static float[] skyColor() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.getRenderViewEntity() == null) return new float[]{0.5f, 0.7f, 1.0f};
        net.minecraft.util.math.Vec3d v = mc.world.getSkyColor(mc.getRenderViewEntity(), mc.getRenderPartialTicks());
        return new float[]{(float) v.x, (float) v.y, (float) v.z};
    }

    static void destroy() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
    }
}
