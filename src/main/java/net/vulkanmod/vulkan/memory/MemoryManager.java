package net.vulkanmod.vulkan.memory;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.util.Pair;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public abstract class MemoryManager {
    private static final VkDevice device = Vulkan.getDevice();
    private static final long allocator = Vulkan.getAllocator();
    private static final int Frames = Vulkan.getSwapChainImages().size();

    /**
     * Everything needed to destroy a buffer once its grace period expires. The handle,
     * allocation and mapped pointer are snapshotted at enqueue time because several
     * buffer types resize in place (addToFreeable(this) followed by a fresh createBuffer
     * on the same object); reading the live Buffer at free time would destroy the new
     * VkBuffer instead of the old one.
     */
    private record PendingFree(Buffer.BufferInfo info, PointerBuffer data) {}

    private static List<Pair<Pair<Long, Long>, Integer>> freeableBuffers = new ArrayList<>();
    private static List<Pair<PendingFree, Integer>> freeableBuffers2 = new ArrayList<>();
    private static List<Pair<Runnable, Integer>> freeableTasks = new ArrayList<>();
//    private static Map<Long, Boolean[]> buffersInUse = new HashMap<>();

    // Live VMA objects, handle -> allocation. vmaDestroyAllocator does NOT destroy
    // allocations that are still alive, so cleanUp must free everything left here
    // before tearing the allocator down (see {@link #destroyAllResources()}).
    private static final Long2LongOpenHashMap buffers = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap images = new Long2LongOpenHashMap();

    private static int deviceMemory = 0;
    private static int nativeMemory = 0;

    public static void createBuffer(long size, int usage, int properties, LongBuffer pBuffer, PointerBuffer pBufferMemory) {

        try(MemoryStack stack = stackPush()) {

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.create();
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);
            //bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
//
            VmaAllocationCreateInfo allocationInfo  = VmaAllocationCreateInfo.create();
            //allocationInfo.usage(VMA_MEMORY_USAGE_CPU_ONLY);
            allocationInfo.requiredFlags(properties);

            int result = vmaCreateBuffer(allocator, bufferInfo, allocationInfo, pBuffer, pBufferMemory, null);
            if(result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create buffer:" + result);
            }

//            LongBuffer pBufferMem = MemoryUtil.memLongBuffer(MemoryUtil.memAddressSafe(pBufferMemory), 1);
//
//            if(vkCreateBuffer(device, bufferInfo, null, pBuffer) != VK_SUCCESS) {
//                throw new RuntimeException("Failed to create vertex buffer");
//            }
//
//            VkMemoryRequirements memRequirements = VkMemoryRequirements.create(stack);
//            vkGetBufferMemoryRequirements(device, pBuffer.get(0), memRequirements);
//
//            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.create();
//            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
//            allocInfo.allocationSize(memRequirements.size());
//            allocInfo.memoryTypeIndex(findMemoryType(memRequirements.memoryTypeBits(), properties));
//
//            if(vkAllocateMemory(device, allocInfo, null, pBufferMem) != VK_SUCCESS) {
//                throw new RuntimeException("Failed to allocate vertex buffer memory");
//            }
//
//            vkBindBufferMemory(device, pBuffer.get(0), pBufferMem.get(0), 0);

            if((properties & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) > 0) {
                deviceMemory += size;
            } else {
                nativeMemory += size;
            }

            buffers.put(pBuffer.get(0), pBufferMemory.get(0));
        }
    }

    /**
     * VMA-tuned host-writeable buffer: with AUTO + SEQUENTIAL_WRITE VMA picks a
     * DEVICE_LOCAL|HOST_VISIBLE type when the BAR lets the CPU write into VRAM (ReBAR),
     * and a host-coherent type otherwise - instead of always snooping system RAM over
     * PCIe. The allocation stays vmaMapMemory-able either way.
     *
     * <p>HOST_COHERENT is a required flag (M10): nothing in this codebase calls
     * vmaFlushAllocation, so a non-coherent pick would leave CPU writes invisible to
     * the GPU. The flag also keeps VMA from falling back to a cached-but-non-coherent
     * system-RAM type.</p>
     */
    public static void createBufferHostAccess(long size, int usage, LongBuffer pBuffer, PointerBuffer pBufferMemory) {

        try(MemoryStack stack = stackPush()) {

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
            allocationInfo.usage(VMA_MEMORY_USAGE_AUTO);
            allocationInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            allocationInfo.requiredFlags(VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            int result = vmaCreateBuffer(allocator, bufferInfo, allocationInfo, pBuffer, pBufferMemory, null);
            if(result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create buffer:" + result);
            }

            // The chosen heap is only known after the fact: ask VMA which memory the
            // allocation ended up in for the usage counters.
            java.nio.IntBuffer memFlags = stack.mallocInt(1);
            vmaGetAllocationMemoryProperties(allocator, pBufferMemory.get(0), memFlags);
            if((memFlags.get(0) & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                deviceMemory += size;
            } else {
                nativeMemory += size;
            }

            buffers.put(pBuffer.get(0), pBufferMemory.get(0));
        }
    }

    public static void createImage(int width, int height, int mipLevel, int format, int tiling, int usage, int memProperties,
                                   LongBuffer pTextureImage, PointerBuffer pTextureImageMemory) {

        try(MemoryStack stack = stackPush()) {

            VkImageCreateInfo imageInfo = VkImageCreateInfo.create();
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);
            imageInfo.extent().width(width);
            imageInfo.extent().height(height);
            imageInfo.extent().depth(1);
            imageInfo.mipLevels(mipLevel);
            imageInfo.arrayLayers(1);
            imageInfo.format(format);
            imageInfo.tiling(tiling);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(usage);
            imageInfo.samples(VK_SAMPLE_COUNT_1_BIT);
            // EXCLUSIVE: the images are only ever used from the graphics/present queue
            // family pair (the swapchain switches to CONCURRENT itself when the families
            // differ). CONCURRENT with a hardcoded {0,1} family list is invalid on GPUs
            // with a single queue family and disables DCC/HTILE compression on the rest.
            imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocationInfo  = VmaAllocationCreateInfo.create();
            //allocationInfo.usage(VMA_MEMORY_USAGE_CPU_ONLY);
            allocationInfo.requiredFlags(memProperties);

            int result = vmaCreateImage(allocator, imageInfo, allocationInfo, pTextureImage, pTextureImageMemory, null);
            if(result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create image:" + result);
            }

            images.put(pTextureImage.get(0), pTextureImageMemory.get(0));

//            if(vkCreateImage(device, imageInfo, null, pTextureImage) != VK_SUCCESS) {
//                throw new RuntimeException("Failed to create image");
//            }
//
//            VkMemoryRequirements memRequirements = VkMemoryRequirements.create(stack);
//            vkGetImageMemoryRequirements(device, pTextureImage.get(0), memRequirements);
//
//            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.create();
//            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
//            allocInfo.allocationSize(memRequirements.size());
//            allocInfo.memoryTypeIndex(findMemoryType(memRequirements.memoryTypeBits(), memProperties));
//
//            if(vkAllocateMemory(device, allocInfo, null, pTextureImageMemory) != VK_SUCCESS) {
//                throw new RuntimeException("Failed to allocate image memory");
//            }
//
//            vkBindImageMemory(device, pTextureImage.get(0), pTextureImageMemory.get(0), 0);
        }
    }

    public static void MapAndCopy(long allocation, long bufferSize, Consumer<PointerBuffer> consumer){

        try(MemoryStack stack = stackPush()) {
            PointerBuffer data = stack.mallocPointer(1);

//            vkMapMemory(Vulkan.getDevice(), allocation, 0, bufferSize, 0, data);
//            consumer.accept(data);
//            vkUnmapMemory(Vulkan.getDevice(), allocation);

            vmaMapMemory(allocator, allocation, data);
            consumer.accept(data);
            vmaUnmapMemory(allocator, allocation);
        }

    }

    public static PointerBuffer Map(long allocation) {
        PointerBuffer data = MemoryUtil.memAllocPointer(1);

        vmaMapMemory(allocator, allocation, data);

        return data;
    }

    public static void Copy(PointerBuffer data, Consumer<PointerBuffer> consumer) {
        consumer.accept(data);
    }

    public synchronized static void freeBuffer(long buffer, long allocation) {
//            vkFreeMemory(device, allocation, null);
//            vkDestroyBuffer(device, buffer, null);

        // Unregister here too: ad-hoc callers (readback/staging dumps) destroy buffers
        // directly, and destroyAllResources must not destroy them a second time.
        buffers.remove(buffer);
        vmaDestroyBuffer(allocator, buffer, allocation);

    }

    public static void freeImage(long image, long allocation) {
//        vkFreeMemory(device, allocation, null);
//        vkDestroyBuffer(device, buffer, null);

        images.remove(image);
        vmaDestroyImage(allocator, image, allocation);
    }

    public synchronized static void addToFreeable(long buffer, long allocation) {
        buffers.remove(buffer);
        freeableBuffers.add(new Pair<>(new Pair<>(buffer, allocation), 0));
    }

    public synchronized static void addToFreeable(Buffer buffer) {
        Buffer.BufferInfo bufferInfo = buffer.getBufferInfo();

        if(buffers.containsKey(bufferInfo.id())){
            // Snapshot the mapped pointer too: Map() allocated a native PointerBuffer for
            // it, and in-place resizes replace buffer.data, leaking the old mapping.
            freeableBuffers2.add(new Pair<>(new PendingFree(bufferInfo, buffer.data), 0));

            buffers.remove(bufferInfo.id());
        }
        else {
            System.err.println("trying to free not present buffer");
//            Thread.dumpStack();
        }

    }

    /**
     * Queues an arbitrary destruction task with the same Frames+1 frame delay as the
     * buffers, so resources read by frames still in flight (images, views) are only
     * destroyed once those frames have finished.
     */
    public synchronized static void addToFreeable(Runnable freeTask) {
        freeableTasks.add(new Pair<>(freeTask, 0));
    }

    public synchronized static void freeBuffers() {
        List<Pair<Pair<Long, Long>, Integer>> newList = new ArrayList<>();

        int waitCount = Frames + 1;
        for(Pair<Pair<Long, Long>, Integer> pair : freeableBuffers) {

            Integer count = pair.second;
            if(count >= waitCount) {
                freeBuffer(pair.first.first, pair.first.second);
            } else {
                pair.second = count + 1;
                newList.add(pair);
            }
        }

        freeableBuffers = newList;

        List<Pair<PendingFree, Integer>> newList2 = new ArrayList<>();

        waitCount = Frames + 1;
        for(Pair<PendingFree, Integer> pair : freeableBuffers2) {

            Integer count = pair.second;
            if(count >= waitCount) {
                PendingFree pending = pair.first;
                Buffer.BufferInfo info = pending.info();
                freeBuffer(info.id(), info.allocation());

                // Release the mapped-pointer buffer Map() allocated; vmaDestroyBuffer
                // above already unmapped the allocation (chunk VBOs churn constantly).
                if(pending.data() != null) {
                    MemoryUtil.memFree(pending.data());
                }

                if(info.type() == MemoryType.Type.DEVICE_LOCAL) {
                    deviceMemory -= info.bufferSize();
                } else {
                    nativeMemory -= info.bufferSize();
                }
            } else {
                pair.second = count + 1;
                newList2.add(pair);
            }
        }

        freeableBuffers2 = newList2;

        List<Pair<Runnable, Integer>> newList3 = new ArrayList<>();

        for(Pair<Runnable, Integer> pair : freeableTasks) {

            Integer count = pair.second;
            if(count >= waitCount) {
                pair.first.run();
            } else {
                pair.second = count + 1;
                newList3.add(pair);
            }
        }

        freeableTasks = newList3;
    }

    /**
     * Destroys every VMA object still alive at shutdown: pending frees whose grace
     * period has not elapsed, deferred tasks, then all registered images and buffers.
     * vmaDestroyAllocator does not destroy live allocations, so this must run while
     * the device is still valid. Image views must already be gone (see
     * {@link net.vulkanmod.vulkan.texture.VulkanImage#destroyAllViews()}).
     */
    public synchronized static void destroyAllResources() {
        for(Pair<Pair<Long, Long>, Integer> pair : freeableBuffers) {
            freeBuffer(pair.first.first, pair.first.second);
        }
        freeableBuffers.clear();

        for(Pair<PendingFree, Integer> pair : freeableBuffers2) {
            PendingFree pending = pair.first;
            freeBuffer(pending.info().id(), pending.info().allocation());
            if(pending.data() != null) {
                MemoryUtil.memFree(pending.data());
            }
        }
        freeableBuffers2.clear();

        for(Pair<Runnable, Integer> pair : freeableTasks) {
            pair.first.run();
        }
        freeableTasks.clear();

        for(Long2LongMap.Entry entry : images.long2LongEntrySet()) {
            vmaDestroyImage(allocator, entry.getLongKey(), entry.getLongValue());
        }
        images.clear();

        for(Long2LongMap.Entry entry : buffers.long2LongEntrySet()) {
            vmaDestroyBuffer(allocator, entry.getLongKey(), entry.getLongValue());
        }
        buffers.clear();
    }

    public static int findMemoryType(int typeFilter, int properties) {

        VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.create();
        vkGetPhysicalDeviceMemoryProperties(Vulkan.getDevice().getPhysicalDevice(), memProperties);

        for(int i = 0;i < memProperties.memoryTypeCount();i++) {
            if((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }

        throw new RuntimeException("Failed to find suitable memory type");
    }

    public static int getNativeMemory() { return nativeMemory; }

    public static int getDeviceMemory() { return deviceMemory; }
}
