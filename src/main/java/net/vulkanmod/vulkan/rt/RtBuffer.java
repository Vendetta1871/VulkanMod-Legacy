package net.vulkanmod.vulkan.rt;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/** A VMA buffer with its device address; optionally persistently mapped (host visible). */
final class RtBuffer {

    final long buffer;
    final long allocation;
    final long size;
    final long address;
    /** Mapped memory for host-visible buffers, else null. */
    final ByteBuffer mapped;

    private RtBuffer(long buffer, long allocation, long size, long address, ByteBuffer mapped) {
        this.buffer = buffer;
        this.allocation = allocation;
        this.size = size;
        this.address = address;
        this.mapped = mapped;
    }

    static RtBuffer create(long size, int usage, boolean hostVisible) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
            if (hostVisible) {
                alloc.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
            } else {
                alloc.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            }
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
            int rc = vmaCreateBuffer(Vulkan.getAllocator(), info, alloc, pBuffer, pAlloc, allocInfo);
            if (rc != VK_SUCCESS) throw new RuntimeException("RT buffer allocation failed: " + rc + " (" + size + " bytes)");

            long buffer = pBuffer.get(0);
            long address = vkGetBufferDeviceAddress(Vulkan.getDevice(),
                    VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
            ByteBuffer mapped = hostVisible ? memByteBuffer(allocInfo.pMappedData(), (int) size) : null;
            return new RtBuffer(buffer, pAlloc.get(0), size, address, mapped);
        }
    }

    static long addressOf(long buffer) {
        try (MemoryStack stack = stackPush()) {
            return vkGetBufferDeviceAddress(Vulkan.getDevice(),
                    VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
        }
    }

    void destroyNow() {
        vmaDestroyBuffer(Vulkan.getAllocator(), buffer, allocation);
    }

    // --- deferred destruction: objects the GPU may still use are released a few frames later ---

    /** Frames to wait; more than any frames-in-flight count the renderer uses. */
    private static final int DELAY = 4;
    private static final ArrayDeque<Object[]> pending = new ArrayDeque<>();
    private static long frame;

    /** Queues {@code release} to run once no in-flight frame can use the object. */
    static void defer(Runnable release) {
        pending.add(new Object[]{frame + DELAY, release});
    }

    /** Called once per frame. */
    static void tick() {
        frame++;
        while (!pending.isEmpty() && (long) pending.peek()[0] <= frame) {
            ((Runnable) pending.poll()[1]).run();
        }
    }

    /** Runs every pending release; the device must be idle. */
    static void flushAll() {
        while (!pending.isEmpty()) ((Runnable) pending.poll()[1]).run();
    }
}
