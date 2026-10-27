package net.vulkanmod.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * Optional device features, queried through the pNext chain and enabled only when
 * present. Render features check these flags, never GPU names.
 */
public final class DeviceFeatures {

    /** min(instance, device) API version the device was created with. */
    public int apiVersion;

    // Core 1.0
    public boolean samplerAnisotropy, wideLines, multiDrawIndirect, logicOp;
    public boolean independentBlend, shaderInt64, fragmentStoresAndAtomics, depthClamp;
    public boolean storageImageReadWithoutFormat, storageImageWriteWithoutFormat;

    // Core 1.2
    public boolean timelineSemaphore, bufferDeviceAddress, scalarBlockLayout, hostQueryReset;
    /** partiallyBound + runtimeDescriptorArray + update-after-bind + non-uniform sampled-image indexing. */
    public boolean descriptorIndexing;

    // Core 1.3
    public boolean dynamicRendering, synchronization2, maintenance4;

    /** acceleration_structure + ray_tracing_pipeline + deferred_host_operations, all enabled. */
    public boolean rayTracingPipeline;
    public boolean rayQuery;

    // Ray tracing properties, valid when rayTracingPipeline is set
    public int shaderGroupHandleSize, shaderGroupBaseAlignment, shaderGroupHandleAlignment;
    public int maxRayRecursionDepth, maxShaderGroupStride;
    public long maxGeometryCount, maxInstanceCount, maxPrimitiveCount;
    public int minAccelerationStructureScratchOffsetAlignment;

    /**
     * Extra usage for buffers that ray tracing reads as acceleration-structure build input
     * (T2: chunk arena, index buffers): device address + AS build input. 0 without RT.
     */
    public static int rtInputBufferUsage() {
        DeviceInfo info = Vulkan.getDeviceInfo();
        if (info == null || info.features == null || !info.features.rayTracingPipeline) return 0;
        return org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
    }

    /** Device extensions to enable on top of the required ones. */
    final Set<String> extensions = new LinkedHashSet<>();

    /**
     * Queries what the device supports and builds the enable chain.
     * Returns the address of a VkPhysicalDeviceFeatures2 for VkDeviceCreateInfo.pNext
     * (pEnabledFeatures must then stay NULL), allocated on {@code stack}.
     */
    long configure(VkPhysicalDevice physicalDevice, int instanceApiVersion, Set<String> available, MemoryStack stack) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(physicalDevice, props);
        // Strip the patch number so comparisons against VK_API_VERSION_1_x work.
        apiVersion = Math.min(instanceApiVersion, props.apiVersion()) & ~0xFFF;

        boolean v12 = apiVersion >= VK_API_VERSION_1_2;
        boolean v13 = apiVersion >= VK_API_VERSION_1_3;
        // RT needs SPIR-V 1.4 and BDA, both core in 1.2.
        boolean rtExts = v12
                && available.contains(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)
                && available.contains(VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME)
                && available.contains(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        boolean rqExt = rtExts && available.contains(VK_KHR_RAY_QUERY_EXTENSION_NAME);

        // --- query ---
        VkPhysicalDeviceFeatures2 sup = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        VkPhysicalDeviceVulkan12Features sup12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
        VkPhysicalDeviceVulkan13Features sup13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
        VkPhysicalDeviceAccelerationStructureFeaturesKHR supAs = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR supRtp = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceRayQueryFeaturesKHR supRq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();

        long chain = 0;
        if (v12) { sup12.pNext(chain); chain = sup12.address(); }
        if (v13) { sup13.pNext(chain); chain = sup13.address(); }
        if (rtExts) {
            supAs.pNext(chain); chain = supAs.address();
            supRtp.pNext(chain); chain = supRtp.address();
        }
        if (rqExt) { supRq.pNext(chain); chain = supRq.address(); }
        sup.pNext(chain);

        if (apiVersion >= VK_API_VERSION_1_1) {
            vkGetPhysicalDeviceFeatures2(physicalDevice, sup);
        } else {
            vkGetPhysicalDeviceFeatures(physicalDevice, sup.features());
        }
        VkPhysicalDeviceFeatures f = sup.features();

        // --- enable ---
        VkPhysicalDeviceFeatures2 en = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        VkPhysicalDeviceFeatures e = en.features();

        e.samplerAnisotropy(samplerAnisotropy = f.samplerAnisotropy());
        e.wideLines(wideLines = f.wideLines());
        e.multiDrawIndirect(multiDrawIndirect = f.multiDrawIndirect());
        e.logicOp(logicOp = f.logicOp());
        e.independentBlend(independentBlend = f.independentBlend());
        e.depthClamp(depthClamp = f.depthClamp());
        e.shaderInt64(shaderInt64 = f.shaderInt64());
        e.fragmentStoresAndAtomics(fragmentStoresAndAtomics = f.fragmentStoresAndAtomics());
        e.shaderStorageImageReadWithoutFormat(storageImageReadWithoutFormat = f.shaderStorageImageReadWithoutFormat());
        e.shaderStorageImageWriteWithoutFormat(storageImageWriteWithoutFormat = f.shaderStorageImageWriteWithoutFormat());
        // ffx-api picks its 16-bit shader permutations from what the device supports.
        e.shaderInt16(f.shaderInt16());

        // ffx-api resolves some functions by their KHR names (vkGetBufferMemoryRequirements2KHR),
        // which the loader only returns when the promoted extension is enabled explicitly.
        for (String ext : new String[]{
                org.lwjgl.vulkan.KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
                org.lwjgl.vulkan.KHRDedicatedAllocation.VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
                org.lwjgl.vulkan.KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,
                org.lwjgl.vulkan.EXTSubgroupSizeControl.VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME}) {
            if (available.contains(ext)) extensions.add(ext);
        }

        chain = 0;
        if (v12) {
            VkPhysicalDeviceVulkan12Features en12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            en12.timelineSemaphore(timelineSemaphore = sup12.timelineSemaphore());
            en12.bufferDeviceAddress(bufferDeviceAddress = sup12.bufferDeviceAddress());
            en12.scalarBlockLayout(scalarBlockLayout = sup12.scalarBlockLayout());
            en12.hostQueryReset(hostQueryReset = sup12.hostQueryReset());
            en12.shaderFloat16(sup12.shaderFloat16());

            descriptorIndexing = sup12.descriptorIndexing()
                    && sup12.descriptorBindingPartiallyBound()
                    && sup12.runtimeDescriptorArray()
                    && sup12.descriptorBindingVariableDescriptorCount()
                    && sup12.descriptorBindingSampledImageUpdateAfterBind()
                    && sup12.descriptorBindingUpdateUnusedWhilePending()
                    && sup12.shaderSampledImageArrayNonUniformIndexing();
            if (descriptorIndexing) {
                en12.descriptorIndexing(true)
                        .descriptorBindingPartiallyBound(true)
                        .runtimeDescriptorArray(true)
                        .descriptorBindingVariableDescriptorCount(true)
                        .descriptorBindingSampledImageUpdateAfterBind(true)
                        .descriptorBindingUpdateUnusedWhilePending(true)
                        .shaderSampledImageArrayNonUniformIndexing(true);
                // Optional extras used by RT hit shaders when present.
                en12.descriptorBindingStorageBufferUpdateAfterBind(sup12.descriptorBindingStorageBufferUpdateAfterBind());
                en12.shaderStorageBufferArrayNonUniformIndexing(sup12.shaderStorageBufferArrayNonUniformIndexing());
            }
            en12.pNext(chain); chain = en12.address();
        }
        if (v13) {
            VkPhysicalDeviceVulkan13Features en13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            en13.dynamicRendering(dynamicRendering = sup13.dynamicRendering());
            en13.synchronization2(synchronization2 = sup13.synchronization2());
            en13.maintenance4(maintenance4 = sup13.maintenance4());
            // ffx-api requests wave sizes when the device reports subgroup size control.
            en13.subgroupSizeControl(sup13.subgroupSizeControl());
            en13.computeFullSubgroups(sup13.computeFullSubgroups());
            en13.pNext(chain); chain = en13.address();
        }

        rayTracingPipeline = rtExts && bufferDeviceAddress
                && supAs.accelerationStructure() && supRtp.rayTracingPipeline();
        if (rayTracingPipeline) {
            VkPhysicalDeviceAccelerationStructureFeaturesKHR enAs = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
            enAs.accelerationStructure(true);
            enAs.pNext(chain); chain = enAs.address();

            VkPhysicalDeviceRayTracingPipelineFeaturesKHR enRtp = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
            enRtp.rayTracingPipeline(true);
            enRtp.rayTraversalPrimitiveCulling(supRtp.rayTraversalPrimitiveCulling());
            enRtp.pNext(chain); chain = enRtp.address();

            extensions.add(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
            extensions.add(VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
            extensions.add(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);

            rayQuery = rqExt && supRq.rayQuery();
            if (rayQuery) {
                VkPhysicalDeviceRayQueryFeaturesKHR enRq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
                enRq.rayQuery(true);
                enRq.pNext(chain); chain = enRq.address();
                extensions.add(VK_KHR_RAY_QUERY_EXTENSION_NAME);
            }

            queryRayTracingProperties(physicalDevice, stack);
        }

        en.pNext(chain);
        return en.address();
    }

    /**
     * The frame is built from dynamic rendering and sync2 barriers (B2/B6), so devices
     * without Vulkan 1.3 and both features are not suitable.
     */
    static boolean meetsBaseline(VkPhysicalDevice physicalDevice, int instanceApiVersion) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            if (Math.min(instanceApiVersion, props.apiVersion() & ~0xFFF) < VK_API_VERSION_1_3) {
                return false;
            }
            VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 f = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(f13.address());
            vkGetPhysicalDeviceFeatures2(physicalDevice, f);
            return f13.dynamicRendering() && f13.synchronization2();
        }
    }

    private void queryRayTracingProperties(VkPhysicalDevice physicalDevice, MemoryStack stack) {
        VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtp = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceAccelerationStructurePropertiesKHR asp = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
        rtp.pNext(asp.address());
        VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
        props2.pNext(rtp.address());
        vkGetPhysicalDeviceProperties2(physicalDevice, props2);

        shaderGroupHandleSize = rtp.shaderGroupHandleSize();
        shaderGroupBaseAlignment = rtp.shaderGroupBaseAlignment();
        shaderGroupHandleAlignment = rtp.shaderGroupHandleAlignment();
        maxRayRecursionDepth = rtp.maxRayRecursionDepth();
        maxShaderGroupStride = rtp.maxShaderGroupStride();
        maxGeometryCount = asp.maxGeometryCount();
        maxInstanceCount = asp.maxInstanceCount();
        maxPrimitiveCount = asp.maxPrimitiveCount();
        minAccelerationStructureScratchOffsetAlignment = asp.minAccelerationStructureScratchOffsetAlignment();
    }

    @Override
    public String toString() {
        return String.format("Vulkan %d.%d; dynamicRendering=%b sync2=%b timeline=%b bda=%b descriptorIndexing=%b "
                        + "independentBlend=%b storageImageRead/WriteWithoutFormat=%b/%b scalarBlockLayout=%b "
                        + "rayTracingPipeline=%b rayQuery=%b%s",
                VK_API_VERSION_MAJOR(apiVersion), VK_API_VERSION_MINOR(apiVersion),
                dynamicRendering, synchronization2, timelineSemaphore, bufferDeviceAddress, descriptorIndexing,
                independentBlend, storageImageReadWithoutFormat, storageImageWriteWithoutFormat, scalarBlockLayout,
                rayTracingPipeline, rayQuery,
                rayTracingPipeline ? String.format(" (handle=%d baseAlign=%d recursion=%d)",
                        shaderGroupHandleSize, shaderGroupBaseAlignment, maxRayRecursionDepth) : "");
    }
}
