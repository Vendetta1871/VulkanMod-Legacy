package net.vesu.vulkan;

import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

/**
 * The Vulkan 1.3 commands the frame is built from, with their KHR spellings for
 * Vulkan 1.2 devices that have VK_KHR_dynamic_rendering and VK_KHR_synchronization2
 * (M2). LWJGL loads the core and the KHR entry points separately, so on a 1.2 device
 * the core pointers are null.
 */
public final class VkCompat {

    /** True when the device was created with Vulkan 1.3; set by {@link DeviceFeatures}. */
    static boolean core13 = true;

    public static void beginRendering(VkCommandBuffer cmd, VkRenderingInfo info) {
        if (core13) VK13.vkCmdBeginRendering(cmd, info);
        else KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
    }

    public static void endRendering(VkCommandBuffer cmd) {
        if (core13) VK13.vkCmdEndRendering(cmd);
        else KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
    }

    public static void pipelineBarrier2(VkCommandBuffer cmd, VkDependencyInfo info) {
        if (core13) VK13.vkCmdPipelineBarrier2(cmd, info);
        else KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, info);
    }

    public static void writeTimestamp2(VkCommandBuffer cmd, long stage, long queryPool, int query) {
        if (core13) VK13.vkCmdWriteTimestamp2(cmd, stage, queryPool, query);
        else KHRSynchronization2.vkCmdWriteTimestamp2KHR(cmd, stage, queryPool, query);
    }

    private VkCompat() {
    }
}
