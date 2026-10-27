package net.vulkanmod.vulkan;

import net.vulkanmod.render.CameraHistory;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * F1: camera motion vectors (previous UV - current UV, jitter-free, render resolution)
 * reconstructed from the scene depth and {@link CameraHistory}. Static geometry only;
 * moving entities get per-draw vectors later.
 */
public final class MotionVectorPass {

    /** VULKANMOD_DEBUG_VIEW=mv shows the (amplified) vectors instead of the scene. */
    static final boolean DEBUG_VIEW = "mv".equalsIgnoreCase(System.getenv("VULKANMOD_DEBUG_VIEW"));

    private static boolean enabled = DEBUG_VIEW;

    static final StorageImage motionVectors = new StorageImage(VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);

    private static ComputeSets sets;
    private static ComputePipeline pipeline;
    private static int boundGeneration = -1;

    private static final Matrix4f jitteredInverse = new Matrix4f();
    private static final Matrix4f toPrev = new Matrix4f();
    private static final Matrix4f toCur = new Matrix4f();

    private MotionVectorPass() {}

    public static boolean enabled() { return enabled; }
    /** Turned on by consumers (temporal upscaler). */
    public static void setEnabled(boolean on) { enabled = on || DEBUG_VIEW; }

    private static void prepare() {
        if (pipeline == null) {
            sets = new ComputeSets(1);
            java.util.Map<String, String> defines = DEBUG_VIEW
                    ? java.util.Map.of("DEBUG_VIEW", "1") : java.util.Map.of();
            ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile("motion_vectors.comp",
                    ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER, defines);
            pipeline = new ComputePipeline(spv, new long[]{sets.layout}, 128);
            spv.free();
        }
        if (boundGeneration == FrameTargets.generation()) return;
        RenderTarget scene = FrameTargets.scene;
        motionVectors.ensure(scene.width(), scene.height());
        sets.write(0, ComputeSets.nearestClampSampler(), scene.depthView(),
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, motionVectors.view);
        boundGeneration = FrameTargets.generation();
    }

    /**
     * Records the pass right after the scene phase ended. Leaves the scene depth and the
     * vectors in SHADER_READ_ONLY_OPTIMAL (ffx-api's layout for COMPUTE_READ).
     */
    static void record(VkCommandBuffer cmd, int depthAspect, MemoryStack stack) {
        prepare();
        RenderTarget scene = FrameTargets.scene;

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.depthImage(), depthAspect,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        // Previous readers of the vectors: the upscaler's compute or the debug blit.
        FrameTargets.imageBarrier(pre.get(1), motionVectors.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        ByteBuffer constants = stack.malloc(128);
        computeMatrices(scene.width(), scene.height());
        toPrev.get(0, constants);
        toCur.get(64, constants);

        pipeline.bind(cmd);
        sets.bind(cmd, pipeline.layout(), 0);
        vkCmdPushConstants(cmd, pipeline.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
        pipeline.dispatch(cmd, (scene.width() + 7) / 8, (scene.height() + 7) / 8, 1);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), motionVectors.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    /**
     * toCur = VP * (J * VP)^-1 and toPrev = prevVP * T(cam - prevCam) * (J * VP)^-1, where
     * J is the clip-space jitter translation the scene was rendered with.
     */
    private static void computeMatrices(int width, int height) {
        Matrix4f vp = CameraHistory.viewProj();
        jitteredInverse.identity()
                .m30(2.0f * CameraHistory.jitterX() / width)
                .m31(-2.0f * CameraHistory.jitterY() / height)
                .mul(vp)
                .invert();

        vp.mul(jitteredInverse, toCur);
        if (CameraHistory.hasPrevious()) {
            toPrev.set(CameraHistory.prevViewProj())
                    .translate((float) (CameraHistory.camX() - CameraHistory.prevCamX()),
                            (float) (CameraHistory.camY() - CameraHistory.prevCamY()),
                            (float) (CameraHistory.camZ() - CameraHistory.prevCamZ()))
                    .mul(jitteredInverse);
        } else {
            toPrev.set(toCur);
        }
    }

    static void destroy() {
        motionVectors.destroy();
        if (pipeline != null) { pipeline.cleanUp(); pipeline = null; }
        if (sets != null) { sets.destroy(); sets = null; }
        boundGeneration = -1;
    }
}
