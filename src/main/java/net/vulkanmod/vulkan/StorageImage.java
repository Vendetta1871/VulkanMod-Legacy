package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** A single-mip 2D color image with one view, for compute passes. */
final class StorageImage {
    final int format;
    final int usage;
    long image, memory, view;
    int width, height;

    StorageImage(int format, int usage) {
        this.format = format;
        this.usage = usage;
    }

    /** (Re)creates the image when the extent changed; the GPU must not use the old one. */
    boolean ensure(int w, int h) {
        if (image != 0L && w == width && h == height) return false;
        destroy();
        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            MemoryManager.createImage(w, h, 1, format, VK_IMAGE_TILING_OPTIMAL, usage,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            image = pImage.get(0);
            memory = pMemory.get(0);
            view = Vulkan.createImageView(image, format, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        }
        width = w;
        height = h;
        return true;
    }

    void destroy() {
        if (view != 0L) vkDestroyImageView(Vulkan.getDevice(), view, null);
        if (image != 0L) MemoryManager.freeImage(image, memory);
        view = image = memory = 0L;
        width = height = 0;
    }
}
