package net.vulkanmod.render;

import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.TransferQueue;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

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

    /** Growth cap: offsets are ints, and 1 GiB of section geometry is far past any world. */
    private static final int MAX_CAPACITY = 1024 * 1024 * 1024;

    private long buffer;
    private long memory;
    private int capacity;
    private int highWater;

    /**
     * Mature free ranges, sorted by offset, packed as {@code offset << 32 | size}, kept
     * disjoint and coalesced. A packed long[] rather than a TreeMap: the first-fit scan and
     * the coalesce's neighbour lookups run on every chunk rebuild and must not box (M22).
     */
    private long[] freeBlocks = new long[64];
    private int freeCount;

    /**
     * Bumped by {@link #reset()} so frees arriving from the previous world's VBOs (which
     * vanilla may still delete after the world swap) are ignored instead of punching holes
     * into freshly handed-out ranges.
     */
    private long generation;

    /**
     * Ranges awaiting reuse: {offset, size, frameAtWhichReusable}. A range freed by a chunk
     * rebuild must not be handed out again until every frame that could still be reading it
     * has retired; uploads run before the frame's in-flight fence, so a fixed delay of
     * {@code swapchainImages + 1} frames is used (mirrors {@code MemoryManager}'s deferred
     * buffer free). A FIFO ring of {@code offset << 32 | size, frame} pairs, so a free does
     * not allocate.
     */
    private long[] pending = new long[2 * 64];
    private int pendingHead;
    private int pendingCount;
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
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                            // T2: BLAS builds read section geometry by device address.
                            | net.vulkanmod.vulkan.DeviceFeatures.rtInputBufferUsage(),
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
        for (int i = 0; i < freeCount; ++i) {
            long block = freeBlocks[i];
            int blockSize = (int) block;
            if (blockSize >= size) {
                int offset = (int) (block >>> 32);
                if (blockSize > size) {
                    // Hand back the leftover tail in place: it stays ordered (its offset
                    // grew but never reaches the next block's). Removing the entry instead
                    // would hand the same offset out again on a later alloc, overlapping
                    // live sections (the "floating islands" bug).
                    freeBlocks[i] = (long) (offset + size) << 32 | (blockSize - size);
                } else {
                    removeBlock(i);
                }
                return offset;
            }
        }

        long needed = (long) highWater + size;
        if (needed > capacity) {
            grow(needed);
        }
        int offset = highWater;
        highWater += size;
        return offset;
    }

    /**
     * Returns a previously allocated range to the arena. The range is only made reusable after
     * {@code freeDelay} frames (see {@link #pending}), so an in-flight frame that is still
     * reading it is never overwritten. Frees from before the last {@link #reset()} are ignored.
     */
    public void free(int offset, int bytes, long generation) {
        int size = align(bytes);
        if (size <= 0 || generation != this.generation) {
            return;
        }
        if (2 * pendingCount == pending.length) {
            long[] grown = new long[pending.length * 2];
            for (int i = 0; i < pendingCount; ++i) {
                int src = (pendingHead + 2 * i) % pending.length;
                grown[2 * i] = pending[src];
                grown[2 * i + 1] = pending[src + 1];
            }
            pending = grown;
            pendingHead = 0;
        }
        int tail = (pendingHead + 2 * pendingCount) % pending.length;
        pending[tail] = (long) offset << 32 | size;
        pending[tail + 1] = frameId + freeDelay;
        ++pendingCount;
    }

    /** The token {@link #free} requires; changes on every {@link #reset()}. */
    public long generation() {
        return generation;
    }

    /** Advances the frame clock and releases any ranges whose delay has elapsed. */
    public void tick() {
        ++frameId;
        while (pendingCount > 0 && pending[pendingHead + 1] <= frameId) {
            long range = pending[pendingHead];
            pendingHead = (pendingHead + 2) % pending.length;
            --pendingCount;
            insertFree((int) (range >>> 32), (int) range);
        }
    }

    /**
     * Drops every allocation: the world is gone and its sections were dropped without
     * freeing their ranges, so the arena would keep the old world's footprint forever
     * (M22). The in-flight frames may still read the arena through the last indirect
     * draws, so they retire first; after that a fresh world can start allocating from 0.
     * The VkBuffer itself (and its grown capacity) is kept.
     */
    public void reset() {
        Drawer.waitForAllFrames();
        TransferQueue.waitPendingFences();

        ++generation;
        // Every section is gone; the frames that used their BLASes were waited for above.
        if (net.vulkanmod.vulkan.rt.RtScene.enabled()) {
            net.vulkanmod.vulkan.rt.RtScene.onArenaReset();
        }
        freeCount = 0;
        pendingHead = 0;
        pendingCount = 0;
        highWater = 0;
    }

    /** Inserts a mature range into the free list, coalescing with adjacent free blocks. */
    private void insertFree(int offset, int size) {
        int start = offset;
        int end = offset + size;

        int i = lowerBound(start);
        boolean mergedPred = i > 0 && (int) (freeBlocks[i - 1] >>> 32) + (int) freeBlocks[i - 1] == start;
        if (mergedPred) {
            start = (int) (freeBlocks[i - 1] >>> 32);
        }
        boolean mergedSucc = i < freeCount && (int) (freeBlocks[i] >>> 32) == end;
        if (mergedSucc) {
            end += (int) freeBlocks[i];
        }

        long merged = (long) start << 32 | (end - start) & 0xFFFFFFFFL;
        if (mergedPred) {
            freeBlocks[i - 1] = merged;
            if (mergedSucc) {
                removeBlock(i);
            }
        } else if (mergedSucc) {
            freeBlocks[i] = merged;
        } else {
            insertBlock(i, merged);
        }

        // Tail merge: a free block that reaches the bump pointer is not fragmentation but
        // unused capacity - give it back instead of letting churn force a grow (M22). Safe
        // because free blocks never overlap a pending range, so nothing above the new
        // high water (including every pending range) can still be referenced.
        if (freeCount > 0) {
            long last = freeBlocks[freeCount - 1];
            int lastOffset = (int) (last >>> 32);
            if (lastOffset + (int) last == highWater) {
                highWater = lastOffset;
                removeBlock(freeCount - 1);
            }
        }
    }

    /** Index of the first block whose offset is >= {@code offset}. */
    private int lowerBound(int offset) {
        int lo = 0, hi = freeCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if ((int) (freeBlocks[mid] >>> 32) < offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void insertBlock(int index, long block) {
        if (freeCount == freeBlocks.length) {
            long[] grown = new long[freeBlocks.length * 2];
            System.arraycopy(freeBlocks, 0, grown, 0, freeCount);
            freeBlocks = grown;
        }
        System.arraycopy(freeBlocks, index, freeBlocks, index + 1, freeCount - index);
        freeBlocks[index] = block;
        ++freeCount;
    }

    private void removeBlock(int index) {
        --freeCount;
        System.arraycopy(freeBlocks, index + 1, freeBlocks, index, freeCount - index);
    }

    /** Copies {@code bytes} from {@code data} into the arena at {@code offset} (staging upload). */
    public void upload(int offset, ByteBuffer data, int bytes) {
        // Staging safety for uploads outside the frame window is enforced inside
        // StagingBuffer.copyBuffer (see Drawer.ensureStagingFree).
        StagingBuffer staging = Vulkan.getStagingBuffer(Drawer.getCurrentFrame());
        staging.copyBuffer(bytes, data);
        copyStagingtoLocalBuffer(staging.getBufferId(), staging.getOffset(), buffer, offset, bytes);
    }

    private void grow(long needed) {
        // Long math: capacity * 2 overflows negative past 1 GiB, and Math.max would then
        // silently pick the (still too small) needed value, turning every later alloc into
        // another grow (M22).
        long alignedNeeded = (needed + (long) stride - 1) / stride * stride;
        long target = Math.max(alignedNeeded, Math.min((long) capacity * 2, MAX_CAPACITY));
        if (target > Integer.MAX_VALUE - stride) {
            throw new OutOfMemoryError("ChunkArena cannot grow to " + target + " bytes");
        }
        int newCapacity = (int) target;

        net.vulkanmod.Initializer.LOGGER.warn("[arena] GROW {}MB -> {}MB (used {}MB, needed {}MB)",
                capacity / (1024 * 1024), newCapacity / (1024 * 1024),
                highWater / (1024 * 1024), needed / (1024 * 1024));

        long oldBuffer = this.buffer;
        long oldMemory = this.memory;

        create(newCapacity);

        // Move the live region to the new buffer device-to-device; offsets stay valid.
        if (highWater > 0) {
            VkCommandBuffer frameCmd = Drawer.getFrameTransferCmd();
            if (frameCmd != null) {
                // The copy rides the frame's transfer command buffer together with the
                // plain uploads of the same frame. Transfer commands do not order
                // against each other, so bracket it with TRANSFER->TRANSFER barriers:
                // RAW against the uploads it must copy (they wrote the old buffer),
                // WAW against the uploads that follow into reused arena regions.
                transferBarrier(frameCmd);
                copyStagingtoLocalBuffer(oldBuffer, 0, this.buffer, 0, highWater);
                transferBarrier(frameCmd);
            } else {
                // Out-of-frame: uploads still pending in the TransferQueue may write the
                // old buffer. Queue submit order already runs this copy after them, but
                // waiting here retires them explicitly so the copy cannot depend on
                // cross-submit ordering the drivers are free to interpret loosely.
                TransferQueue.waitPendingFences();
                copyStagingtoLocalBuffer(oldBuffer, 0, this.buffer, 0, highWater);
                // Immediate submit: retire it before returning, so the next copy issued
                // on this queue cannot overlap it.
                TransferQueue.waitPendingFences();
            }
        }
        // Defer freeing the old buffer until the copy has retired.
        MemoryManager.addToFreeable(oldBuffer, oldMemory);
    }

    private static void transferBarrier(VkCommandBuffer commandBuffer) {
        // Global TRANSFER write-after-write/write-after-read dependency between the
        // copies recorded before and after it. The memory barrier is what makes the
        // writes visible: stage masks alone order execution but do not flush caches.
        try(MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);

            vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, barrier,
                    (org.lwjgl.vulkan.VkBufferMemoryBarrier.Buffer) null,
                    (org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer) null);
        }
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
