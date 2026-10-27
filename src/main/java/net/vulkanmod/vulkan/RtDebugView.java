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
 * VULKANMOD_DEBUG_VIEW=rt (T7 check): traces primary rays against {@link RtScene}'s TLAS
 * at render resolution and shows the result instead of the raster scene: textured albedo,
 * alpha-tested cutout geometry, sun/moon diffuse with ray-traced shadows.
 */
public final class RtDebugView {

    public static final boolean ACTIVE = "rt".equalsIgnoreCase(System.getenv("VULKANMOD_DEBUG_VIEW"));

    private static final int PUSH_BYTES = 80;

    private static RtPipeline pipeline;

    static final StorageImage output = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);

    private static final Matrix4f invViewProj = new Matrix4f();

    private RtDebugView() {}

    /**
     * Traces the debug image at render resolution and leaves it in GENERAL. Returns false
     * when nothing was traced (no TLAS or atlas yet).
     */
    static boolean record(VkCommandBuffer cmd, MemoryStack stack) {
        if (!RtScene.ready() || net.vulkanmod.render.TextureBridge.getAtlasImage() == null) return false;
        if (pipeline == null) {
            pipeline = new RtPipeline(RtDescriptors.layout(), PUSH_BYTES, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/debug.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit")});
            Initializer.LOGGER.info("[rt] debug view ready");
        }
        RenderTarget scene = FrameTargets.scene;
        output.ensure(scene.width(), scene.height());

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(pre.get(0), output.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        long set = RtDescriptors.write(RtDescriptors.PASS_DEBUG, output.view, 0L, 0L);

        CameraHistory.viewProj().invert(invViewProj);
        ByteBuffer constants = stack.malloc(PUSH_BYTES);
        invViewProj.get(0, constants);
        float[] sun = RtLighting.sun();
        constants.putFloat(64, sun[0]).putFloat(68, sun[1]).putFloat(72, sun[2]).putFloat(76, sun[3]);

        pipeline.trace(cmd, set, constants, scene.width(), scene.height());
        return true;
    }

    static void destroy() {
        output.destroy();
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
    }
}
