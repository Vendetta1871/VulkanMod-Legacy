package net.vulkanmod.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A compute pipeline: shader module, pipeline layout and the pipeline itself.
 * Descriptor sets are owned by the caller and passed in as layouts.
 */
public class ComputePipeline {
    private static final VkDevice device = Vulkan.getDevice();

    private final long pipeline;
    private final long pipelineLayout;

    /**
     * @param spirv              compiled compute shader
     * @param descriptorSetLayouts layouts of the sets bound at indices 0..n-1, may be null
     * @param pushConstantSize   push constant range size for the compute stage, 0 for none
     */
    public ComputePipeline(ShaderSPIRVUtils.SPIRV spirv, long[] descriptorSetLayouts, int pushConstantSize) {
        long layout;
        long handle;

        try(MemoryStack stack = stackPush()) {
            long shaderModule = createShaderModule(stack, spirv.bytecode());
            try {
                layout = createPipelineLayout(stack, descriptorSetLayouts, pushConstantSize);
                handle = createPipeline(stack, shaderModule, layout);
            } finally {
                // vkCreateComputePipelines keeps no reference to the module.
                vkDestroyShaderModule(device, shaderModule, null);
            }
        }

        this.pipelineLayout = layout;
        this.pipeline = handle;
    }

    public long handle() {
        return pipeline;
    }

    public long layout() {
        return pipelineLayout;
    }

    public void bind(VkCommandBuffer cmd) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    }

    public void dispatch(VkCommandBuffer cmd, int x, int y, int z) {
        vkCmdDispatch(cmd, x, y, z);
    }

    public void cleanUp() {
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
    }

    private static long createShaderModule(MemoryStack stack, ByteBuffer code) {
        VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);
        createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
        createInfo.pCode(code);

        LongBuffer pShaderModule = stack.mallocLong(1);
        if(vkCreateShaderModule(device, createInfo, null, pShaderModule) != VK_SUCCESS) {
            throw new RuntimeException("Failed to create compute shader module");
        }
        return pShaderModule.get(0);
    }

    private static long createPipelineLayout(MemoryStack stack, long[] descriptorSetLayouts,
                                             int pushConstantSize) {
        VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
        layoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);

        if(descriptorSetLayouts != null && descriptorSetLayouts.length > 0) {
            layoutInfo.pSetLayouts(stack.longs(descriptorSetLayouts));
        }

        if(pushConstantSize > 0) {
            VkPushConstantRange.Buffer pushConstantRange = VkPushConstantRange.calloc(1, stack);
            pushConstantRange.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            pushConstantRange.offset(0);
            pushConstantRange.size(pushConstantSize);

            layoutInfo.pPushConstantRanges(pushConstantRange);
        }

        LongBuffer pPipelineLayout = stack.mallocLong(1);
        if(vkCreatePipelineLayout(device, layoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
            throw new RuntimeException("Failed to create compute pipeline layout");
        }
        return pPipelineLayout.get(0);
    }

    private static long createPipeline(MemoryStack stack, long shaderModule, long pipelineLayout) {
        VkPipelineShaderStageCreateInfo shaderStage = VkPipelineShaderStageCreateInfo.calloc(stack);
        shaderStage.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
        shaderStage.stage(VK_SHADER_STAGE_COMPUTE_BIT);
        shaderStage.module(shaderModule);
        shaderStage.pName(stack.UTF8("main"));

        VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
        VkComputePipelineCreateInfo info = pipelineInfo.get(0);
        info.sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
        info.stage(shaderStage);
        info.layout(pipelineLayout);
        info.basePipelineHandle(VK_NULL_HANDLE);
        info.basePipelineIndex(-1);

        LongBuffer pPipeline = stack.mallocLong(1);
        // Same driver cache as the graphics pipelines, so it is persisted with them.
        if(vkCreateComputePipelines(device, Pipeline.getPipelineCache(), pipelineInfo, null, pPipeline) != VK_SUCCESS) {
            throw new RuntimeException("Failed to create compute pipeline");
        }
        return pPipeline.get(0);
    }
}
