package net.vesu.lod.gpu;

import net.vesu.Initializer;
import net.vesu.vulkan.Drawer;
import net.vesu.vulkan.FrameTargets;
import net.vesu.vulkan.Pipeline;
import net.vesu.vulkan.ShaderSPIRVUtils;
import net.vesu.vulkan.VRenderSystem;
import net.vesu.vulkan.Vulkan;
import net.vesu.vulkan.memory.MemoryManager;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * SSAO for LOD pixels without a shader pack (AUDIT 5.15). A compute pass reads the scene
 * depth after the LOD opaque pass and writes an occlusion term; a full-screen draw then
 * multiplies the scene color by it. AUTO picks the sample count by the device: integrated
 * GPUs and less than 2.5 GB of device-local memory get BASIC, less than 7 GB BETTER (12
 * samples), otherwise BEST (24). Render thread only.
 */
public final class LodSsao {
    private static final int BASIC = 6, BETTER = 12, BEST = 24;

    private final VkDevice device = Vulkan.getDevice();
    private final int frames = Drawer.framesInFlight();
    private final int autoSamples;

    private long image, alloc, view;
    private int width, height;
    private long computeLayout, applyLayout, pool, sampler;
    private final long[] computeSets, applySets;
    private net.vesu.vulkan.ComputePipeline compute;
    private long applyPipelineLayout, applyPipeline;
    private int applyDepthFormat;
    private final Matrix4f inv = new Matrix4f();

    public LodSsao() {
        computeSets = new long[frames];
        applySets = new long[frames];
        autoSamples = pickAuto();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(4, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p), "ssao layout");
            computeLayout = p.get(0);
            VkDescriptorSetLayoutBinding.Buffer a = VkDescriptorSetLayoutBinding.calloc(1, stack);
            a.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(a), null, p), "ssao apply layout");
            applyLayout = p.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(frames);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(frames);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(frames * 2);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(frames);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(frames * 2).pPoolSizes(sizes), null, p), "ssao pool");
            pool = p.get(0);
            LongBuffer layouts = stack.mallocLong(frames * 2);
            for (int i = 0; i < frames; i++) {
                layouts.put(i, computeLayout);
                layouts.put(frames + i, applyLayout);
            }
            LongBuffer out = stack.mallocLong(frames * 2);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "ssao sets");
            for (int i = 0; i < frames; i++) {
                computeSets[i] = out.get(i);
                applySets[i] = out.get(frames + i);
            }
            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0);
            check(vkCreateSampler(device, si, null, p), "ssao sampler");
            sampler = p.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(applyLayout)), null, p), "ssao apply pipeline layout");
            applyPipelineLayout = p.get(0);
        }
        Initializer.LOGGER.info("[lod] SSAO auto: {} samples", autoSamples);
    }

    private static int pickAuto() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice pd = Vulkan.getDevice().getPhysicalDevice();
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(pd, props);
            if (props.deviceType() != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) return BASIC;
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(pd, mp);
            long local = 0;
            for (int i = 0; i < mp.memoryHeapCount(); i++) {
                VkMemoryHeap h = mp.memoryHeaps(i);
                if ((h.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) local = Math.max(local, h.size());
            }
            if (local < (5L << 29)) return BASIC;
            if (local < (7L << 30)) return BETTER;
            return BEST;
        }
    }

    /** Samples for the configured mode, 0 when off. */
    public int samples() {
        return switch (Initializer.CONFIG.lodSsao) {
            case "off" -> 0;
            case "basic" -> BASIC;
            case "better" -> BETTER;
            case "best" -> BEST;
            default -> autoSamples;
        };
    }

    public String modeLabel() {
        int s = samples();
        String m = Initializer.CONFIG.lodSsao;
        return s == 0 ? "off" : m + (m.equals("auto") ? " (" + s + ")" : "");
    }

    private void ensureImage(int w, int h) {
        if (w == width && h == height && image != 0) return;
        destroyImage();
        width = w;
        height = h;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pi = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            MemoryManager.createImage(w, h, 1, VK_FORMAT_R32_SFLOAT, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_STORAGE_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pi, pa);
            image = pi.get(0);
            alloc = pa.get(0);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R32_SFLOAT);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, vi, null, pi), "ssao view");
            view = pi.get(0);
        }
    }

    private void destroyImage() {
        if (image == 0) return;
        long img = image, al = alloc, v = view;
        MemoryManager.addToFreeable(() -> {
            vkDestroyImageView(device, v, null);
            MemoryManager.freeImage(img, al);
        });
        image = 0;
    }

    private void ensurePipelines() {
        if (compute == null) {
            ShaderSPIRVUtils.SPIRV cs = ShaderSPIRVUtils.compileShaderFile("lod/ssao.comp", ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
            if (cs == null) throw new IllegalStateException("LOD SSAO shader failed to compile");
            compute = new net.vesu.vulkan.ComputePipeline(cs, new long[]{computeLayout}, 80);
        }
        if (applyPipeline != 0 && applyDepthFormat == FrameTargets.depthFormat()) return;
        if (applyPipeline != 0) {
            long old = applyPipeline;
            MemoryManager.addToFreeable(() -> vkDestroyPipeline(device, old, null));
        }
        ShaderSPIRVUtils.SPIRV vs = ShaderSPIRVUtils.compileShaderFile("lod/ssao_apply.vsh", ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER);
        ShaderSPIRVUtils.SPIRV fs = ShaderSPIRVUtils.compileShaderFile("lod/ssao_apply.fsh", ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        if (vs == null || fs == null) throw new IllegalStateException("LOD SSAO apply shaders failed to compile");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long vm = module(stack, vs.bytecode()), fm = module(stack, fs.bytecode());
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(stack.UTF8("main"));
            // dst * src for color, dst alpha kept.
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(0xF).blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_ZERO).dstColorBlendFactor(VK_BLEND_FACTOR_SRC_COLOR).colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE).alphaBlendOp(VK_BLEND_OP_ADD);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                    .pStages(stages)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                            .lineWidth(1.0f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                            .depthTestEnable(false).depthWriteEnable(false))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(applyPipelineLayout)
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                            .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(FrameTargets.COLOR_FORMAT))
                            .depthAttachmentFormat(FrameTargets.depthFormat()).stencilAttachmentFormat(VK_FORMAT_UNDEFINED).address());
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, Pipeline.getPipelineCache(), info, null, p), "ssao apply pipeline");
            applyPipeline = p.get(0);
            vkDestroyShaderModule(device, vm, null);
            vkDestroyShaderModule(device, fm, null);
        }
        applyDepthFormat = FrameTargets.depthFormat();
    }

    private long module(MemoryStack stack, ByteBuffer code) {
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, p), "shader module");
        return p.get(0);
    }

    /**
     * Inside scene rendering, right after the LOD opaque draws: leaves rendering, computes
     * the occlusion, resumes and multiplies the scene color by it.
     */
    public void record(VkCommandBuffer cmd, int frame, long ubo, long cover, Matrix4f viewProj, int samples) {
        int w = FrameTargets.scene.width(), h = FrameTargets.scene.height();
        // Half resolution: one occlusion texel per 2x2 pixels.
        int aw = (w + 1) / 2, ah = (h + 1) / 2;
        ensureImage(aw, ah);
        ensurePipelines();
        viewProj.invert(inv);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FrameTargets.suspendSceneForCompute(cmd, stack);

            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(5, stack);
            wr.get(0).sType$Default().dstSet(computeSets[frame]).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(ubo).offset(0).range(VK_WHOLE_SIZE));
            wr.get(1).sType$Default().dstSet(computeSets[frame]).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler)
                            .imageView(FrameTargets.scene.depthView()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            wr.get(2).sType$Default().dstSet(computeSets[frame]).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            wr.get(3).sType$Default().dstSet(computeSets[frame]).dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(cover).offset(0).range(VK_WHOLE_SIZE));
            wr.get(4).sType$Default().dstSet(applySets[frame]).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            vkUpdateDescriptorSets(device, wr, null);

            // The previous frame's apply read may still run: wait for fragment reads, then write.
            VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
            ib.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, ib);

            compute.bind(cmd);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, compute.layout(), 0, stack.longs(computeSets[frame]), null);
            ByteBuffer pc = stack.malloc(80);
            inv.get(0, pc);
            pc.putInt(64, w).putInt(68, h).putInt(72, samples).putInt(76, VRenderSystem.reverseZ ? 1 : 0);
            vkCmdPushConstants(cmd, compute.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            vkCmdDispatch(cmd, (aw + 7) / 8, (ah + 7) / 8, 1);

            ib.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, ib);

            FrameTargets.resumeScene(cmd, stack);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, applyPipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, applyPipelineLayout, 0, stack.longs(applySets[frame]), null);
            vkCmdDraw(cmd, 3, 1, 0, 0);
            Drawer.forgetBoundPipeline(cmd);
        }
    }

    public void destroy() {
        destroyImage();
        net.vesu.vulkan.ComputePipeline c = compute;
        long ap = applyPipeline, apl = applyPipelineLayout, cl = computeLayout, al = applyLayout, pl = pool, s = sampler;
        MemoryManager.addToFreeable(() -> {
            if (c != null) c.cleanUp();
            if (ap != 0) vkDestroyPipeline(device, ap, null);
            vkDestroyPipelineLayout(device, apl, null);
            vkDestroyDescriptorPool(device, pl, null);
            vkDestroyDescriptorSetLayout(device, cl, null);
            vkDestroyDescriptorSetLayout(device, al, null);
            vkDestroySampler(device, s, null);
        });
    }

    private static void check(int rc, String what) {
        if (rc != VK_SUCCESS) throw new IllegalStateException("LOD " + what + ": " + rc);
    }
}
