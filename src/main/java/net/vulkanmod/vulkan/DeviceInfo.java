package net.vulkanmod.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;

public class DeviceInfo {

    public static final String cpuInfo;

    private final VkPhysicalDevice device;
    public final String vendorId;
    public final String deviceName;
    public final String driverVersion;
    /** Set once the logical device is created (B1). */
    public DeviceFeatures features;

    static {
        cpuInfo = System.getProperty("os.arch", "unknown");
    }

    public DeviceInfo(VkPhysicalDevice device, VkPhysicalDeviceProperties properties) {
        this.device = device;
        this.vendorId = String.valueOf(properties.vendorID());
        this.deviceName = properties.deviceNameString();
        this.driverVersion = String.valueOf(properties.driverVersion());
    }

    private String unsupportedExtensions(Set<String> requiredExtensions) {

        try(MemoryStack stack = stackPush()) {

            IntBuffer extensionCount = stack.ints(0);

            vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, null);

            VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.create(extensionCount.get(0));

            vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, availableExtensions);

            Set<String> extensions = availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet());

            // Work on a copy: the caller passes one shared set and calls this per physical
            // device, so removing from it would under-report device 2+ as fully supported.
            Set<String> missing = new java.util.HashSet<>(requiredExtensions);
            missing.removeAll(extensions);

            return "Unsupported extensions: " + Arrays.toString(missing.toArray());
        }
    }

    public static String debugString(PointerBuffer ppPhysicalDevices, Set<String> requiredExtensions, VkInstance instance) {
        try (MemoryStack stack = stackPush()) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("\n");

            for(int i = 0; i < ppPhysicalDevices.capacity();i++) {
                VkPhysicalDevice device = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance);

                VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties.create();
                vkGetPhysicalDeviceProperties(device, deviceProperties);

                DeviceInfo info = new DeviceInfo(device, deviceProperties);

                stringBuilder.append(String.format("Device %d: ", i)).append(info.deviceName).append("\n");
                stringBuilder.append(info.unsupportedExtensions(requiredExtensions)).append("\n");

                Vulkan.SwapChainSupportDetails swapChainSupport = Vulkan.querySwapChainSupport(device, stack);
                boolean swapChainAdequate = swapChainSupport.formats != null && swapChainSupport.formats.hasRemaining()
                        && swapChainSupport.presentModes != null && swapChainSupport.presentModes.hasRemaining();
                stringBuilder.append("Swapchain supported: ").append(swapChainAdequate ? "true" : "false").append("\n");
            }

            return stringBuilder.toString();
        }
    }

}
