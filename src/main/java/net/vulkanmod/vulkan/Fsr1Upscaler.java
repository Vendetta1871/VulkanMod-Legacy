package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * FSR 1 (F0): spatial EASU upscale + RCAS sharpening in compute, using AMD's
 * ffx_fsr1.h. scene color -> intermediate (EASU) -> display color (RCAS).
 * The fallback upscaler when the native ffx-api library is unavailable.
 */
final class Fsr1Upscaler {

    /** RCAS sharpness in stops: 0 = maximum; AMD's default is 0.2. */
    private static final float SHARPNESS = readSharpness();

    private static long sampler;
    private static long setLayout;
    private static long descriptorPool;
    private static long easuSet, rcasSet;
    private static ComputePipeline easu, rcas;

    private static long interImage, interMemory, interView;
    private static int interW, interH;

    private Fsr1Upscaler() {}

    private static float readSharpness() {
        String env = System.getenv("VULKANMOD_FSR_SHARPNESS");
        try {
            return env != null ? Math.max(0.0f, Math.min(2.0f, Float.parseFloat(env))) : 0.2f;
        } catch (NumberFormatException e) {
            return 0.2f;
        }
    }

    private static void init() {
        VkDevice device = Vulkan.getDevice();
        try (MemoryStack stack = stackPush()) {
            // EASU gathers with normalized coordinates; clamp keeps the border taps inside.
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSampler(device, samplerInfo, null, p), "sampler");
            sampler = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, p), "set layout");
            setLayout = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(2).pPoolSizes(sizes), null, p), "descriptor pool");
            descriptorPool = p.get(0);

            LongBuffer sets = stack.mallocLong(2);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout, setLayout)), sets), "sets");
            easuSet = sets.get(0);
            rcasSet = sets.get(1);
        }

        long[] layouts = {setLayout};
        ShaderSPIRVUtils.SPIRV easuSpv = ShaderSPIRVUtils.compileShaderFile("fsr1_easu.comp",
                ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        easu = new ComputePipeline(easuSpv, layouts, 64);
        easuSpv.free();
        ShaderSPIRVUtils.SPIRV rcasSpv = ShaderSPIRVUtils.compileShaderFile("fsr1_rcas.comp",
                ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        rcas = new ComputePipeline(rcasSpv, layouts, 16);
        rcasSpv.free();

        Initializer.LOGGER.info("[fsr1] ready, sharpness {}", SHARPNESS);
    }

    /** Makes the intermediate match the display extent and points the sets at the current images. */
    private static void prepare() {
        if (easu == null) init();
        RenderTarget scene = FrameTargets.scene, display = FrameTargets.display;
        if (interImage != 0L && boundGeneration == FrameTargets.generation()) {
            return;
        }

        // Resize waits for the device to idle, so the old intermediate is unused here.
        destroyIntermediate();
        interW = display.width();
        interH = display.height();
        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            MemoryManager.createImage(interW, interH, 1, FrameTargets.COLOR_FORMAT, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            interImage = pImage.get(0);
            interMemory = pMemory.get(0);
            interView = Vulkan.createImageView(interImage, FrameTargets.COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT, 1);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            writeImage(writes.get(0), easuSet, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    scene.colorView(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, stack);
            writeImage(writes.get(1), easuSet, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    interView, VK_IMAGE_LAYOUT_GENERAL, stack);
            writeImage(writes.get(2), rcasSet, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    interView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, stack);
            writeImage(writes.get(3), rcasSet, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    display.colorView(), VK_IMAGE_LAYOUT_GENERAL, stack);
            vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);
        }
        boundGeneration = FrameTargets.generation();
    }

    /** FrameTargets generation the descriptor sets point at; view handles may be reused. */
    private static int boundGeneration = -1;

    private static void writeImage(VkWriteDescriptorSet w, long set, int binding, int type,
                                   long view, int layout, MemoryStack stack) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER ? sampler : VK_NULL_HANDLE)
                .imageView(view).imageLayout(layout);
        w.sType$Default().dstSet(set).dstBinding(binding).descriptorType(type)
                .descriptorCount(1).pImageInfo(info);
    }

    /**
     * Records the upscale. Expects the scene color in COLOR_ATTACHMENT_OPTIMAL (just
     * rendered) and leaves the display color in COLOR_ATTACHMENT_OPTIMAL for the hand/GUI.
     */
    static void record(VkCommandBuffer cmd, MemoryStack stack) {
        prepare();
        RenderTarget scene = FrameTargets.scene, display = FrameTargets.display;

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        // The previous frame's RCAS read the intermediate.
        FrameTargets.imageBarrier(pre.get(1), interImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        ByteBuffer easuCon = stack.malloc(64);
        easuConstants(easuCon, scene.width(), scene.height(), display.width(), display.height());
        easu.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, easu.layout(), 0, stack.longs(easuSet), null);
        vkCmdPushConstants(cmd, easu.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, easuCon);
        easu.dispatch(cmd, (interW + 7) / 8, (interH + 7) / 8, 1);

        VkImageMemoryBarrier2.Buffer mid = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(mid.get(0), interImage, VK_IMAGE_ASPECT_COLOR_BIT,
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
        rcasCon.putInt(0, Float.floatToRawIntBits((float) Math.pow(2.0, -SHARPNESS)));
        rcas.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, rcas.layout(), 0, stack.longs(rcasSet), null);
        vkCmdPushConstants(cmd, rcas.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, rcasCon);
        rcas.dispatch(cmd, (interW + 7) / 8, (interH + 7) / 8, 1);

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
        for (int i = 0; i < 16; i++) b.putInt(i * 4, Float.floatToRawIntBits(c[i]));
        // con3.zw are integer zeros in the reference; 0.0f has the same bits.
    }

    private static void destroyIntermediate() {
        if (interView != 0L) vkDestroyImageView(Vulkan.getDevice(), interView, null);
        if (interImage != 0L) MemoryManager.freeImage(interImage, interMemory);
        interView = interImage = interMemory = 0L;
        boundGeneration = -1;
    }

    static void destroy() {
        destroyIntermediate();
        VkDevice device = Vulkan.getDevice();
        if (easu != null) { easu.cleanUp(); easu = null; }
        if (rcas != null) { rcas.cleanUp(); rcas = null; }
        if (descriptorPool != 0L) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0L; }
        if (setLayout != 0L) { vkDestroyDescriptorSetLayout(device, setLayout, null); setLayout = 0L; }
        if (sampler != 0L) { vkDestroySampler(device, sampler, null); sampler = 0L; }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new RuntimeException("FSR1: failed to create " + what + ": " + result);
    }
}
