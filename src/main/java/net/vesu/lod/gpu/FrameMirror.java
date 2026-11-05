package net.vesu.lod.gpu;

import net.vesu.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/**
 * A table of fixed-size entries the GPU reads every frame (section culling data, the
 * traversal's nodes and children), one host-visible copy per frame in flight. The CPU
 * writes a master copy; a changed entry reaches the current frame's copy at once and every
 * other copy when its frame slot comes round again, once the GPU is done with it. A frame
 * in flight thus never sees an entry change under it: a new mesh whose quads only a later
 * frame uploads, a node that turned ready with a slot its own frame cannot draw yet.
 */
final class FrameMirror {
    private final int stride;
    private final long count;
    private final long master;
    private final long[] buf, alloc, ptr;
    private final int[][] dirty;
    private final int[] dirtyCount;
    /** The frame slot being recorded, -1 before the first frame. */
    private int current = -1;

    FrameMirror(long count, int stride, int frames) {
        this.count = count;
        this.stride = stride;
        long bytes = count * stride;
        master = MemoryUtil.nmemCalloc(1, bytes);
        if (master == 0L) throw new OutOfMemoryError("LOD table: " + bytes + " bytes");
        buf = new long[frames];
        alloc = new long[frames];
        ptr = new long[frames];
        dirty = new int[frames][];
        dirtyCount = new int[frames];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < frames; i++) {
                LongBuffer pb = stack.mallocLong(1);
                PointerBuffer pa = stack.mallocPointer(1);
                MemoryManager.createBufferHostAccess(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, pb, pa);
                buf[i] = pb.get(0);
                alloc[i] = pa.get(0);
                ptr[i] = MemoryManager.Map(pa.get(0)).get(0);
                MemoryUtil.memSet(ptr[i], 0, bytes);
                dirty[i] = new int[1024];
            }
        }
    }

    long buffer(int frame) {
        return buf[frame];
    }

    /** The master address of entry {@code i}: write it, then call {@link #changed}. */
    long at(long i) {
        return master + i * stride;
    }

    void changed(int i) {
        long src = master + (long) i * stride;
        for (int f = 0; f < buf.length; f++) {
            if (f == current) {
                MemoryUtil.memCopy(src, ptr[f] + (long) i * stride, stride);
            } else {
                if (dirtyCount[f] == dirty[f].length) dirty[f] = Arrays.copyOf(dirty[f], dirtyCount[f] * 2);
                dirty[f][dirtyCount[f]++] = i;
            }
        }
    }

    /** Frame slot {@code frame} starts recording (its last frame is done): it takes every change since. */
    void beginFrame(int frame) {
        current = frame;
        int n = dirtyCount[frame];
        if (n == 0) return;
        long dst = ptr[frame];
        if ((long) n * 4 > count) {
            // Most of the table changed (a reload): one copy of the whole.
            MemoryUtil.memCopy(master, dst, count * stride);
        } else {
            int[] d = dirty[frame];
            for (int k = 0; k < n; k++) MemoryUtil.memCopy(master + (long) d[k] * stride, dst + (long) d[k] * stride, stride);
        }
        dirtyCount[frame] = 0;
        if (dirty[frame].length > 65536) dirty[frame] = new int[1024];
    }

    /** Queues the buffers' release; the master goes at once (nothing on the GPU reads it). */
    void destroy() {
        long[] b = buf.clone(), a = alloc.clone();
        MemoryManager.addToFreeable(() -> {
            for (int i = 0; i < b.length; i++) MemoryManager.freeBuffer(b[i], a[i]);
        });
        MemoryUtil.nmemFree(master);
    }
}
