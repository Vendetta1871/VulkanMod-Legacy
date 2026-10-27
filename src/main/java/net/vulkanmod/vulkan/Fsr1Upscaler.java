package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * FSR 1 (F0): spatial EASU upscale + RCAS sharpening in compute, using AMD's
 * ffx_fsr1.h. scene color -> intermediate (EASU) -> display color (RCAS).
 * The fallback upscaler when the native ffx-api library is unavailable.
 */
final class Fsr1Upscaler {

    private static final int EASU = 0, RCAS = 1;

    private static ComputeSets sets;
    private static ComputePipeline easu, rcas;
    private static final StorageImage intermediate = new StorageImage(FrameTargets.COLOR_FORMAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);

    /** FrameTargets generation the descriptor sets point at; view handles may be reused. */
    private static int boundGeneration = -1;

    private Fsr1Upscaler() {}

    private static void init() {
        sets = new ComputeSets(2);
        long[] layouts = {sets.layout};
        ShaderSPIRVUtils.SPIRV easuSpv = ShaderSPIRVUtils.compileShaderFile("fsr1_easu.comp",
                ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        easu = new ComputePipeline(easuSpv, layouts, 64);
        easuSpv.free();
        ShaderSPIRVUtils.SPIRV rcasSpv = ShaderSPIRVUtils.compileShaderFile("fsr1_rcas.comp",
                ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        rcas = new ComputePipeline(rcasSpv, layouts, 16);
        rcasSpv.free();
        Initializer.LOGGER.info("[fsr1] ready");
    }

    /** Makes the intermediate match the display extent and points the sets at the current images. */
    private static void prepare() {
        if (easu == null) init();
        if (boundGeneration == FrameTargets.generation()) return;

        // Resize waits for the device to idle, so the old intermediate is unused here.
        RenderTarget scene = FrameTargets.scene, display = FrameTargets.display;
        intermediate.ensure(display.width(), display.height());
        sets.write(EASU, ComputeSets.linearClampSampler(), scene.colorView(),
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, intermediate.view);
        sets.write(RCAS, ComputeSets.linearClampSampler(), intermediate.view,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, display.colorView());
        boundGeneration = FrameTargets.generation();
    }

    /**
     * Records the upscale. Expects the scene color in COLOR_ATTACHMENT_OPTIMAL (just
     * rendered) and leaves the display color in COLOR_ATTACHMENT_OPTIMAL for the hand/GUI.
     */
    static void record(VkCommandBuffer cmd, MemoryStack stack) {
        prepare();
        RenderTarget scene = FrameTargets.scene, display = FrameTargets.display;
        int groupsX = (display.width() + 7) / 8, groupsY = (display.height() + 7) / 8;

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        // The previous frame's RCAS read the intermediate.
        FrameTargets.imageBarrier(pre.get(1), intermediate.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        ByteBuffer easuCon = stack.malloc(64);
        easuConstants(easuCon, scene.width(), scene.height(), display.width(), display.height());
        easu.bind(cmd);
        sets.bind(cmd, easu.layout(), EASU);
        vkCmdPushConstants(cmd, easu.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, easuCon);
        easu.dispatch(cmd, groupsX, groupsY, 1);

        VkImageMemoryBarrier2.Buffer mid = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(mid.get(0), intermediate.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        // The previous frame's swapchain copy read the display color.
        FrameTargets.imageBarrier(mid.get(1), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, mid, stack);

        ByteBuffer rcasCon = stack.calloc(16);
        float sharpness = Initializer.CONFIG.effectiveFsr1Sharpness();
        rcasCon.putInt(0, Float.floatToRawIntBits((float) Math.pow(2.0, -sharpness)));
        rcas.bind(cmd);
        sets.bind(cmd, rcas.layout(), RCAS);
        vkCmdPushConstants(cmd, rcas.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, rcasCon);
        rcas.dispatch(cmd, groupsX, groupsY, 1);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    /** Port of ffxFsrPopulateEasuConstants (viewport = whole input image). */
    private static void easuConstants(ByteBuffer b, float inW, float inH, float outW, float outH) {
        float[] c = {
                inW / outW, inH / outH, 0.5f * inW / outW - 0.5f, 0.5f * inH / outH - 0.5f,
                1.0f / inW, 1.0f / inH, 1.0f / inW, -1.0f / inH,
                -1.0f / inW, 2.0f / inH, 1.0f / inW, 2.0f / inH,
                0.0f, 4.0f / inH, 0.0f, 0.0f
        };
        // con3.zw are integer zeros in the reference; 0.0f has the same bits.
        for (int i = 0; i < 16; i++) b.putInt(i * 4, Float.floatToRawIntBits(c[i]));
    }

    static void destroy() {
        intermediate.destroy();
        if (easu != null) { easu.cleanUp(); easu = null; }
        if (rcas != null) { rcas.cleanUp(); rcas = null; }
        if (sets != null) { sets.destroy(); sets = null; }
        boundGeneration = -1;
    }
}
