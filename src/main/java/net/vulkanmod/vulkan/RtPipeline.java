package net.vulkanmod.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/**
 * A ray-tracing pipeline with its shader binding table (T7): one raygen, any number of miss
 * shaders and triangle hit groups (closest hit and/or any hit), one descriptor set layout
 * and a push-constant block for the raygen stage.
 */
final class RtPipeline {

    /** A triangle hit group; either shader may be null. */
    record HitGroup(String closestHit, String anyHit) {}

    final long layout;
    final long pipeline;
    private final long sbtBuffer, sbtAllocation;
    private final VkStridedDeviceAddressRegionKHR raygenRegion, missRegion, hitRegion, callableRegion;
    private final int pushStages;

    RtPipeline(long setLayout, int pushBytes, int pushStages, String raygen, String[] misses, HitGroup[] hits) {
        VkDevice device = Vulkan.getDevice();
        DeviceFeatures f = Vulkan.getDeviceInfo().features;
        this.pushStages = pushStages;
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout));
            if (pushBytes > 0) {
                VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
                push.get(0).stageFlags(pushStages).offset(0).size(pushBytes);
                layoutInfo.pPushConstantRanges(push);
            }
            check(vkCreatePipelineLayout(device, layoutInfo, null, p), "pipeline layout");
            layout = p.get(0);

            // Stages: raygen, misses, then the hit shaders in group order.
            List<long[]> stages = new ArrayList<>();   // {module, stage}
            stages.add(new long[]{module(raygen, ShaderSPIRVUtils.ShaderKind.RAYGEN_SHADER, stack), VK_SHADER_STAGE_RAYGEN_BIT_KHR});
            for (String miss : misses) {
                stages.add(new long[]{module(miss, ShaderSPIRVUtils.ShaderKind.MISS_SHADER, stack), VK_SHADER_STAGE_MISS_BIT_KHR});
            }
            int groupCount = 1 + misses.length + hits.length;
            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount, stack);
            for (int i = 0; i < groupCount; i++) {
                groups.get(i).sType$Default().generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(VK_SHADER_UNUSED_KHR)
                        .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR)
                        .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR);
            }
            for (int i = 0; i <= misses.length; i++) groups.get(i).generalShader(i);
            for (int h = 0; h < hits.length; h++) {
                VkRayTracingShaderGroupCreateInfoKHR g = groups.get(1 + misses.length + h)
                        .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR);
                if (hits[h].closestHit() != null) {
                    g.closestHitShader(stages.size());
                    stages.add(new long[]{module(hits[h].closestHit(), ShaderSPIRVUtils.ShaderKind.CLOSEST_HIT_SHADER, stack),
                            VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR});
                }
                if (hits[h].anyHit() != null) {
                    g.anyHitShader(stages.size());
                    stages.add(new long[]{module(hits[h].anyHit(), ShaderSPIRVUtils.ShaderKind.ANY_HIT_SHADER, stack),
                            VK_SHADER_STAGE_ANY_HIT_BIT_KHR});
                }
            }

            VkPipelineShaderStageCreateInfo.Buffer stageInfos = VkPipelineShaderStageCreateInfo.calloc(stages.size(), stack);
            for (int i = 0; i < stages.size(); i++) {
                stageInfos.get(i).sType$Default().stage((int) stages.get(i)[1]).module(stages.get(i)[0])
                        .pName(stack.UTF8("main"));
            }
            VkRayTracingPipelineCreateInfoKHR.Buffer info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            info.get(0).sType$Default().pStages(stageInfos).pGroups(groups)
                    .maxPipelineRayRecursionDepth(1).layout(layout);
            int rc = vkCreateRayTracingPipelinesKHR(device, VK_NULL_HANDLE, VK_NULL_HANDLE, info, null, p);
            for (long[] s : stages) vkDestroyShaderModule(device, s[0], null);
            check(rc, "RT pipeline (" + raygen + ")");
            pipeline = p.get(0);

            // SBT: raygen | misses | hits, each region on the base alignment.
            int handleSize = f.shaderGroupHandleSize;
            int handleStride = (int) align(handleSize, f.shaderGroupHandleAlignment);
            int base = f.shaderGroupBaseAlignment;
            int raygenSize = (int) align(handleStride, base);
            int missSize = (int) align((long) misses.length * handleStride, base);
            int hitSize = (int) align((long) Math.max(1, hits.length) * handleStride, base);
            ByteBuffer handles = stack.malloc(handleSize * groupCount);
            check(vkGetRayTracingShaderGroupHandlesKHR(device, pipeline, 0, groupCount, handles), "group handles");

            long sbtSize = (long) raygenSize + missSize + hitSize + base;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default().size(sbtSize)
                    .usage(VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO)
                    .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
            check(vmaCreateBuffer(Vulkan.getAllocator(), bufferInfo, alloc, p, pAlloc, allocInfo), "SBT buffer");
            sbtBuffer = p.get(0);
            sbtAllocation = pAlloc.get(0);
            long raw = vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(sbtBuffer));
            long address = align(raw, base);
            ByteBuffer mapped = memByteBuffer(allocInfo.pMappedData(), (int) sbtSize);
            int start = (int) (address - raw);
            for (int g = 0; g < groupCount; g++) {
                int dst;
                if (g == 0) dst = start;
                else if (g <= misses.length) dst = start + raygenSize + (g - 1) * handleStride;
                else dst = start + raygenSize + missSize + (g - 1 - misses.length) * handleStride;
                for (int i = 0; i < handleSize; i++) mapped.put(dst + i, handles.get(g * handleSize + i));
            }
            vmaFlushAllocation(Vulkan.getAllocator(), sbtAllocation, 0, sbtSize);

            raygenRegion = VkStridedDeviceAddressRegionKHR.calloc().deviceAddress(address).stride(raygenSize).size(raygenSize);
            missRegion = VkStridedDeviceAddressRegionKHR.calloc().deviceAddress(address + raygenSize)
                    .stride(handleStride).size(missSize);
            hitRegion = VkStridedDeviceAddressRegionKHR.calloc().deviceAddress(address + raygenSize + missSize)
                    .stride(handleStride).size(hits.length == 0 ? 0 : hitSize);
            callableRegion = VkStridedDeviceAddressRegionKHR.calloc();
        }
    }

    void trace(VkCommandBuffer cmd, long set, ByteBuffer push, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, layout, 0, stack.longs(set), null);
            if (push != null) vkCmdPushConstants(cmd, layout, pushStages, 0, push);
            vkCmdTraceRaysKHR(cmd, raygenRegion, missRegion, hitRegion, callableRegion, width, height, 1);
        }
    }

    void destroy() {
        VkDevice device = Vulkan.getDevice();
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, layout, null);
        vmaDestroyBuffer(Vulkan.getAllocator(), sbtBuffer, sbtAllocation);
        raygenRegion.free();
        missRegion.free();
        hitRegion.free();
        callableRegion.free();
    }

    private static long module(String file, ShaderSPIRVUtils.ShaderKind kind, MemoryStack stack) {
        ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile(file, kind);
        if (spv == null) throw new RuntimeException("RT shader " + file + " failed to compile");
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateShaderModule(Vulkan.getDevice(), VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                .pCode(spv.bytecode()), null, p), "shader module " + file);
        spv.free();
        return p.get(0);
    }

    static long align(long v, long a) {
        return (v + a - 1) / a * a;
    }

    static void check(int rc, String what) {
        if (rc != VK_SUCCESS) throw new RuntimeException("RT: " + what + " failed: " + rc);
    }
}
