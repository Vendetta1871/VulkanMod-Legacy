package net.vulkanmod.render;

import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;

import static net.vulkanmod.vulkan.Vulkan.copyStagingtoLocalBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A single persistent device-local vertex buffer that holds the geometry of every chunk
 * section, replacing the one-VkBuffer-per-section model. Sections are handed a byte range by
 * a first-fit free list, so a rebuild releases its old range and the space is reused instead
 * of the arena growing without bound.
 *
 * <p>Allocations are rounded up to the vertex stride so a section's byte offset is always an
 * exact multiple of the stride; the indirect draw's {@code vertexOffset} (expressed in
 * vertices) is then just {@code offset / stride}. The arena can outgrow its capacity by
 * allocating a larger buffer and copying the live region device-to-device, which leaves every
 * previously handed-out offset valid.</p>
 */
public class ChunkArena {

    /** Vertex stride in bytes; also the allocation granularity (keeps vertexOffset integral). */
    private final int stride;

    private long buffer;
    private long memory;
    private int capacity;
    private int highWater;

    /** Mature free ranges as offset -> size, kept disjoint and coalesced. */
    private final TreeMap<Integer, Integer> free = new TreeMap<>();

    /**
     * Ranges awaiting reuse: {offset, size, frameAtWhichReusable}. A range freed by a chunk
     * rebuild must not be handed out again until every frame that could still be reading it
     * has retired; uploads run before the frame's in-flight fence, so a fixed delay of
     * {@code swapchainImages + 1} frames is used (mirrors {@code MemoryManager}'s deferred
     * buffer free).
     */
    private final Deque<long[]> pending = new ArrayDeque<>();
    private long frameId;
    private final int freeDelay;

    public ChunkArena(int initialCapacity, int freeDelay, int stride) {
        this.freeDelay = freeDelay;
        this.stride = stride;
        create(align(initialCapacity));
    }

    private void create(int size) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.pointers(VK_NULL_HANDLE);

            MemoryManager.createBuffer(size,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_HEAP_DEVICE_LOCAL_BIT,
                    pBuffer, pAllocation);

            this.buffer = pBuffer.get(0);
            this.memory = pAllocation.get(0);
        }
        this.capacity = size;
    }

    /** Reserves a range of at least {@code bytes} and returns its byte offset. */
    public int alloc(int bytes) {
        int size = align(bytes);

        // First-fit scan for a free block big enough.
        for (Map.Entry<Integer, Integer> e : free.entrySet()) {
            int block = e.getValue();
            if (block >= size) {
                int offset = e.getKey();
                if (block == size) {
                    free.remove(offset);
                } else {
                    free.put(offset + size, block - size);
                }
                return offset;
            }
        }

        if (highWater + size > capacity) {
            grow(highWater + size);
        }
        int offset = highWater;
        highWater += size;
        return offset;
    }

    /**
     * Returns a previously allocated range to the arena. The range is only made reusable after
     * {@code freeDelay} frames (see {@link #pending}), so an in-flight frame that is still
     * reading it is never overwritten.
     */
    public void free(int offset, int bytes) {
        int size = align(bytes);
        if (size <= 0) {
            return;
        }
        pending.addLast(new long[]{offset, size, frameId + freeDelay});
    }

    /** Advances the frame clock and releases any ranges whose delay has elapsed. */
    public void tick() {
        ++frameId;
        while (!pending.isEmpty() && pending.peekFirst()[2] <= frameId) {
            long[] p = pending.pollFirst();
            insertFree((int) p[0], (int) p[1]);
        }
    }

    /** Inserts a mature range into the free list, coalescing with adjacent free blocks. */
    private void insertFree(int offset, int size) {
        int start = offset;
        int end = offset + size;

        Map.Entry<Integer, Integer> lower = free.lowerEntry(offset);
        if (lower != null && lower.getKey() + lower.getValue() == offset) {
            start = lower.getKey();
            free.remove(lower.getKey());
        }
        Map.Entry<Integer, Integer> higher = free.ceilingEntry(offset);
        if (higher != null && higher.getKey() == end) {
            end = higher.getKey() + higher.getValue();
            free.remove(higher.getKey());
        }
        free.put(start, end - start);
    }

    /** Copies {@code bytes} from {@code data} into the arena at {@code offset} (staging upload). */
    public void upload(int offset, ByteBuffer data, int bytes) {
        // Uploads issued outside the Vulkan frame window must not clobber staging regions
        // still in flight from the previous frame (same guard as the per-chunk VBO path).
        if (!Drawer.isActive()) {
            net.vulkanmod.vulkan.Synchronization.waitFences();
        }
        StagingBuffer staging = Vulkan.getStagingBuffer(Drawer.getCurrentFrame());
        staging.copyBuffer(bytes, data);
        copyStagingtoLocalBuffer(staging.getBufferId(), staging.getOffset(), buffer, offset, bytes);
    }

    private void grow(int needed) {
        int newCapacity = Math.max(align(needed), capacity * 2);

        long oldBuffer = this.buffer;
        long oldMemory = this.memory;

        create(newCapacity);

        // Move the live region to the new buffer device-to-device; offsets stay valid.
        if (highWater > 0) {
            copyStagingtoLocalBuffer(oldBuffer, 0, this.buffer, 0, highWater);
        }
        // Defer freeing the old buffer until the copy has retired.
        MemoryManager.addToFreeable(oldBuffer, oldMemory);
    }

    public long bufferId() {
        return buffer;
    }

    public int capacity() {
        return capacity;
    }

    public int used() {
        return highWater;
    }

    public int stride() {
        return stride;
    }

    private int align(int bytes) {
        return (bytes + stride - 1) / stride * stride;
    }
}
