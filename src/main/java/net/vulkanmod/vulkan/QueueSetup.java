package net.vulkanmod.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Queues created with the device (B1). Frame generation (F9) wants separate game,
 * async-compute, present and image-acquire queues; extra queues are created when the
 * families offer them, otherwise callers fall back to sharing.
 * A shared VkQueue needs external synchronization when used from several threads.
 */
public final class QueueSetup {

    /** Queues wanted from the graphics family: game + two auxiliary (FG present, acquire). */
    private static final int GRAPHICS_QUEUES_WANTED = 3;

    public final int graphicsFamily;
    public final int presentFamily;
    /** Compute-capable family without GRAPHICS, or -1. */
    public final int asyncComputeFamily;

    private final Map<Integer, Integer> counts = new LinkedHashMap<>();

    public VkQueue graphics;
    public VkQueue present;
    /** Dedicated async-compute queue, or {@link #graphics} when the device has none. */
    public VkQueue asyncCompute;
    /** Extra queues of the graphics family beyond index 0 (may be empty). */
    public VkQueue[] auxGraphics = new VkQueue[0];

    private QueueSetup(int graphicsFamily, int presentFamily, int asyncComputeFamily) {
        this.graphicsFamily = graphicsFamily;
        this.presentFamily = presentFamily;
        this.asyncComputeFamily = asyncComputeFamily;
    }

    static QueueSetup plan(VkPhysicalDevice physicalDevice, Vulkan.QueueFamilyIndices indices) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);

            int compute = -1;
            for (int i = 0; i < families.capacity(); i++) {
                int flags = families.get(i).queueFlags();
                if ((flags & VK_QUEUE_COMPUTE_BIT) != 0 && (flags & VK_QUEUE_GRAPHICS_BIT) == 0) {
                    compute = i;
                    break;
                }
            }

            QueueSetup setup = new QueueSetup(indices.graphicsFamily, indices.presentFamily, compute);
            setup.counts.put(indices.graphicsFamily,
                    Math.min(GRAPHICS_QUEUES_WANTED, families.get(indices.graphicsFamily).queueCount()));
            setup.counts.putIfAbsent(indices.presentFamily, 1);
            if (compute >= 0) setup.counts.putIfAbsent(compute, 1);
            return setup;
        }
    }

    int[] families() {
        return counts.keySet().stream().mapToInt(Integer::intValue).toArray();
    }

    int queueCount(int family) {
        return counts.get(family);
    }

    void fetch(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);

            vkGetDeviceQueue(device, graphicsFamily, 0, p);
            graphics = new VkQueue(p.get(0), device);

            // The present queue stays index 0 of its family, as before B1.
            if (presentFamily == graphicsFamily) {
                present = graphics;
            } else {
                vkGetDeviceQueue(device, presentFamily, 0, p);
                present = new VkQueue(p.get(0), device);
            }

            if (asyncComputeFamily >= 0) {
                vkGetDeviceQueue(device, asyncComputeFamily, 0, p);
                asyncCompute = new VkQueue(p.get(0), device);
            } else {
                asyncCompute = graphics;
            }

            int extra = counts.get(graphicsFamily) - 1;
            auxGraphics = new VkQueue[extra];
            for (int i = 0; i < extra; i++) {
                vkGetDeviceQueue(device, graphicsFamily, i + 1, p);
                auxGraphics[i] = new VkQueue(p.get(0), device);
            }
        }
    }

    public boolean hasDedicatedAsyncCompute() {
        return asyncComputeFamily >= 0;
    }

    @Override
    public String toString() {
        return "graphics=" + graphicsFamily + "x" + counts.get(graphicsFamily)
                + " present=" + presentFamily
                + " asyncCompute=" + (asyncComputeFamily >= 0 ? String.valueOf(asyncComputeFamily) : "shared")
                + " families=" + Arrays.toString(families());
    }
}
