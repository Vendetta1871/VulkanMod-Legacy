package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** A color + depth image pair of one extent, used as dynamic-rendering attachments. */
public final class RenderTarget {

    public final String name;
    private int width, height;
    private long colorImage, colorMemory, colorView;
    private long depthImage, depthMemory, depthView;

    RenderTarget(String name) {
        this.name = name;
    }

    public int width() { return width; }
    public int height() { return height; }
    public long colorImage() { return colorImage; }
    public long colorView() { return colorView; }
    public long depthImage() { return depthImage; }
    public long depthView() { return depthView; }

    /** (Re)creates the images; the caller guarantees the GPU no longer uses the old ones. */
    void create(int w, int h, int colorFormat, int colorUsage, int depthFormat) {
        destroy();
        width = w;
        height = h;

        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);

            MemoryManager.createImage(w, h, 1, colorFormat, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | colorUsage,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            colorImage = pImage.get(0);
            colorMemory = pMemory.get(0);
            colorView = Vulkan.createImageView(colorImage, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT, 1);

            // STORE + SAMPLED: FSR, motion-vector reprojection and RT read the depth later.
            MemoryManager.createImage(w, h, 1, depthFormat, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            depthImage = pImage.get(0);
            depthMemory = pMemory.get(0);
            // Depth-only view: valid as the attachment (stencil is never used) and for sampling.
            depthView = Vulkan.createImageView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT, 1);
        }
    }

    void destroy() {
        VkDevice device = Vulkan.getDevice();
        // Views go before the images they were created from (N24).
        if (colorView != 0L) vkDestroyImageView(device, colorView, null);
        if (colorImage != 0L) MemoryManager.freeImage(colorImage, colorMemory);
        if (depthView != 0L) vkDestroyImageView(device, depthView, null);
        if (depthImage != 0L) MemoryManager.freeImage(depthImage, depthMemory);
        colorView = colorImage = colorMemory = 0L;
        depthView = depthImage = depthMemory = 0L;
        width = height = 0;
    }
}
