package net.vulkanmod.vulkan;

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
 * Hybrid ray-traced sun/moon shadows on the raster scene (T8, first effect): one shadow
 * ray per scene pixel from the depth-reconstructed position; shadowed pixels are darkened
 * in place before the upscaler. Strength: Config.rtShadowStrength (VULKANMOD_RT_SHADOWS overrides).
 */
final class RtShadowPass {

    private static final int PUSH_BYTES = 96;

    private static RtPipeline pipeline;
    private static final Matrix4f invJittered = new Matrix4f();

    private RtShadowPass() {}

    static void record(VkCommandBuffer cmd, MemoryStack stack) {
        if (!RtScene.ready() || net.vulkanmod.render.TextureBridge.getAtlasImage() == null || !RtLighting.hasSky()) {
            return;
        }
        float[] sun = RtLighting.sun();
        boolean day = sun[1] >= 0.0f;
        float strength = Initializer.CONFIG.effectiveRtShadowStrength() * (day ? 1.0f : 0.3f) * (1.0f - RtLighting.overcast())
                // Fade out while the sun or moon crosses the horizon.
                * Math.min(1.0f, Math.abs(sun[1]) / 0.1f);
        if (strength <= 0.01f) return;

        if (pipeline == null) {
            pipeline = new RtPipeline(RtDescriptors.layout(), PUSH_BYTES, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/shadows.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit")});
            Initializer.LOGGER.info("[rt] shadows ready");
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
                    VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        }
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        long set = RtDescriptors.write(RtDescriptors.PASS_SHADOWS, scene.colorView(), scene.depthView(),
                // Binding 5 must be valid even unused: the scene color stands in (same layout).
                opaque ? FrameTargets.opaqueColor.view : scene.colorView());

        // The depth was rendered with the jittered projection.
        invJittered.identity()
                .m30(2.0f * CameraHistory.jitterX() / scene.width())
                .m31(-2.0f * CameraHistory.jitterY() / scene.height())
                .mul(CameraHistory.viewProj())
                .invert();
        ByteBuffer constants = stack.calloc(PUSH_BYTES);
        invJittered.get(0, constants);
        float s = day ? 1.0f : -1.0f;
        constants.putFloat(64, sun[0] * s).putFloat(68, sun[1] * s).putFloat(72, sun[2] * s).putFloat(76, strength);
        constants.putFloat(80, opaque ? 1.0f : 0.0f).putFloat(84, RtScene.distanceChunks() * 16.0f);

        pipeline.trace(cmd, set, constants, scene.width(), scene.height());

        // Back to the layouts the upscalers start from.
        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(opaque ? 2 : 1, stack);
        FrameTargets.imageBarrier(post.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        if (opaque) {
            FrameTargets.imageBarrier(post.get(1), FrameTargets.opaqueColor.image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    static void destroy() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
    }
}
