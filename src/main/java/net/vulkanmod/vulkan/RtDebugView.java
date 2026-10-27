package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.CameraHistory;
import net.vulkanmod.vulkan.rt.RtScene;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;

/**
 * VULKANMOD_DEBUG_VIEW=rt (T7 check): traces primary rays against {@link RtScene}'s TLAS
 * at render resolution and shows the result instead of the raster scene. Sections are
 * colored per instance with triangle outlines, so BLAS/TLAS placement can be compared
 * against the raster image.
 */
public final class RtDebugView {

    public static final boolean ACTIVE = "rt".equalsIgnoreCase(System.getenv("VULKANMOD_DEBUG_VIEW"));

    private static final int SETS = 8;

    private static long setLayout, pool, pipelineLayout, pipeline;
    private static final long[] sets = new long[SETS];
    private static long sbtBuffer, sbtAllocation, sbtAddress;
    private static VkStridedDeviceAddressRegionKHR raygenRegion, missRegion, hitRegion, callableRegion;

    static final StorageImage output = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);

    private static final Matrix4f invViewProj = new Matrix4f();

    private RtDebugView() {}

    private static void init() {
        VkDevice device = Vulkan.getDevice();
        DeviceFeatures f = Vulkan.getDeviceInfo().features;
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(bindings), null, p), "set layout");
            setLayout = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(SETS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SETS);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SETS).pPoolSizes(sizes), null, p), "descriptor pool");
            pool = p.get(0);
            LongBuffer layouts = stack.mallocLong(SETS);
            for (int i = 0; i < SETS; i++) layouts.put(i, setLayout);
            LongBuffer out = stack.mallocLong(SETS);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "descriptor sets");
            for (int i = 0; i < SETS; i++) sets[i] = out.get(i);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR).offset(0).size(64);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, p), "pipeline layout");
            pipelineLayout = p.get(0);

            long rgen = module("rt/debug.rgen", ShaderSPIRVUtils.ShaderKind.RAYGEN_SHADER, stack);
            long miss = module("rt/debug.rmiss", ShaderSPIRVUtils.ShaderKind.MISS_SHADER, stack);
            long chit = module("rt/debug.rchit", ShaderSPIRVUtils.ShaderKind.CLOSEST_HIT_SHADER, stack);

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(3, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(rgen).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_MISS_BIT_KHR).module(miss).pName(stack.UTF8("main"));
            stages.get(2).sType$Default().stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(chit).pName(stack.UTF8("main"));

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                groups.get(i).sType$Default().generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(VK_SHADER_UNUSED_KHR)
                        .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            }
            groups.get(0).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0);
            groups.get(1).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(1);
            groups.get(2).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR).closestHitShader(2);

            VkRayTracingPipelineCreateInfoKHR.Buffer info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages).pGroups(groups)
                    .maxPipelineRayRecursionDepth(1).layout(pipelineLayout);
            check(vkCreateRayTracingPipelinesKHR(device, VK_NULL_HANDLE, VK_NULL_HANDLE, info, null, p), "RT pipeline");
            pipeline = p.get(0);
            vkDestroyShaderModule(device, rgen, null);
            vkDestroyShaderModule(device, miss, null);
            vkDestroyShaderModule(device, chit, null);

            // Shader binding table: raygen | miss | hit, each region on the base alignment.
            int handleSize = f.shaderGroupHandleSize;
            int handleStride = (int) align(handleSize, f.shaderGroupHandleAlignment);
            int base = f.shaderGroupBaseAlignment;
            int regionSize = (int) align(handleStride, base);
            ByteBuffer handles = stack.malloc(handleSize * 3);
            check(vkGetRayTracingShaderGroupHandlesKHR(device, pipeline, 0, 3, handles), "group handles");

            long sbtSize = (long) regionSize * 3 + base;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default().size(sbtSize)
                    .usage(VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO)
                    .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
            org.lwjgl.PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
            check(vmaCreateBuffer(Vulkan.getAllocator(), bufferInfo, alloc, p, pAlloc, allocInfo), "SBT buffer");
            sbtBuffer = p.get(0);
            sbtAllocation = pAlloc.get(0);
            long raw = vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(sbtBuffer));
            sbtAddress = align(raw, base);
            ByteBuffer mapped = memByteBuffer(allocInfo.pMappedData(), (int) sbtSize);
            for (int g = 0; g < 3; g++) {
                int dst = (int) (sbtAddress - raw) + g * regionSize;
                for (int i = 0; i < handleSize; i++) mapped.put(dst + i, handles.get(g * handleSize + i));
            }
            vmaFlushAllocation(Vulkan.getAllocator(), sbtAllocation, 0, sbtSize);

            raygenRegion = VkStridedDeviceAddressRegionKHR.calloc().deviceAddress(sbtAddress).stride(regionSize).size(regionSize);
            missRegion = VkStridedDeviceAddressRegionKHR.calloc().deviceAddress(sbtAddress + regionSize).stride(handleStride).size(regionSize);
            hitRegion = VkStridedDeviceAddressRegionKHR.calloc().deviceAddress(sbtAddress + 2L * regionSize).stride(handleStride).size(regionSize);
            callableRegion = VkStridedDeviceAddressRegionKHR.calloc();
        }
        Initializer.LOGGER.info("[rt] debug view ready");
    }

    private static long module(String file, ShaderSPIRVUtils.ShaderKind kind, MemoryStack stack) {
        ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile(file, kind);
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateShaderModule(Vulkan.getDevice(), VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                .pCode(spv.bytecode()), null, p), "shader module " + file);
        spv.free();
        return p.get(0);
    }

    /**
     * Traces the debug image at render resolution and leaves it in GENERAL. Returns false
     * when nothing was traced (no TLAS yet).
     */
    static boolean record(VkCommandBuffer cmd, MemoryStack stack) {
        if (!RtScene.ready()) return false;
        if (pipeline == 0L) init();
        RenderTarget scene = FrameTargets.scene;
        output.ensure(scene.width(), scene.height());

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(pre.get(0), output.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        // The set of this frame slot is free: its previous use retired with the slot's fence.
        long set = sets[Drawer.getCurrentFrame() % SETS];
        VkWriteDescriptorSetAccelerationStructureKHR asInfo = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                .sType$Default().pAccelerationStructures(stack.longs(RtScene.tlas()));
        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
        imageInfo.get(0).imageView(output.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0).sType$Default().pNext(asInfo.address()).dstSet(set).dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(imageInfo);
        vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);

        CameraHistory.viewProj().invert(invViewProj);
        ByteBuffer constants = stack.malloc(64);
        invViewProj.get(0, constants);

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, stack.longs(set), null);
        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_RAYGEN_BIT_KHR, 0, constants);
        vkCmdTraceRaysKHR(cmd, raygenRegion, missRegion, hitRegion, callableRegion, scene.width(), scene.height(), 1);
        return true;
    }

    static void destroy() {
        VkDevice device = Vulkan.getDevice();
        output.destroy();
        if (pipeline != 0L) {
            vkDestroyPipeline(device, pipeline, null);
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorPool(device, pool, null);
            vkDestroyDescriptorSetLayout(device, setLayout, null);
            vmaDestroyBuffer(Vulkan.getAllocator(), sbtBuffer, sbtAllocation);
            raygenRegion.free();
            missRegion.free();
            hitRegion.free();
            callableRegion.free();
            pipeline = 0L;
        }
    }

    private static long align(long v, long a) {
        return (v + a - 1) / a * a;
    }

    private static void check(int rc, String what) {
        if (rc != VK_SUCCESS) throw new RuntimeException("RT debug view: " + what + " failed: " + rc);
    }
}
