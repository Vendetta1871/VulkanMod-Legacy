package net.vesu.lod.gpu;

import net.vesu.vulkan.Drawer;
import net.vesu.vulkan.ShaderSPIRVUtils;
import net.vesu.vulkan.Vulkan;
import net.vesu.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU node traversal (AUDIT 5.11): the node table mirrors the node thread's tree, one
 * compute dispatch per level walks it from the top-level list and writes the candidate
 * list the cull pass turns into draws. The CPU only keeps the table current and answers
 * requests (a node that wants to descend into children the table lacks, or a mesh that
 * never arrived), at most {@link #MAX_REQUESTS} per frame, read once the frame is done.
 *
 * <p>The table, the children blocks, the visibility stamps and the requests live in host
 * memory: the CPU writes the first two in place, and reads the stamps (eviction, cleanup,
 * the RT ring) and the requests without a copy. Ids and blocks are reused only after every
 * frame that may read them is done.</p>
 */
public final class LodTraversal {
    public static final int MAX_NODES = 1 << 18, MAX_BLOCKS = 1 << 16, MAX_REQUESTS = 50;
    private static final int NODE_BYTES = 32, QUEUE = 200000, SORT_MAX = 2048, MAX_ROOTS = 1 << 15, MAX_CANDIDATES = 131072;
    private static final int BINDINGS = 9;
    public static final int NO_SLOT = -1;

    private final VkDevice device = Vulkan.getDevice();
    private final int frames = Drawer.framesInFlight();

    /** The node table and the children blocks, one copy per frame in flight. */
    private final FrameMirror nodes, children;
    private final long stampsBuf, stampsAlloc, stampsPtr;
    private final long[] requestsBuf, requestsAlloc, requestsPtr, rootsBuf, rootsAlloc, rootsPtr;
    private final long queueBuf, queueAlloc, travBuf, travAlloc, sortBuf, sortAlloc, orderBuf, orderAlloc;

    private long setLayout, pool;
    private final long[] sets;
    private net.vesu.vulkan.ComputePipeline traverse, args, sort, order;
    private long mainSetLayout;

    private final int[] freeIds = new int[MAX_NODES];
    private int freeIdCount, nextId = 1;
    private final int[] freeBlocks = new int[MAX_BLOCKS];
    private int freeBlockCount, nextBlock = 1;
    /** {frame number it is free from, kind (0 id, 1 block), value} */
    private final ArrayDeque<long[]> pending = new ArrayDeque<>();
    private long frameNo;

    public LodTraversal() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            nodes = new FrameMirror(MAX_NODES, NODE_BYTES, frames);
            children = new FrameMirror(MAX_BLOCKS, 32, frames);
            long[] r = host(stack, (long) MAX_NODES * 8);
            stampsBuf = r[0]; stampsAlloc = r[1]; stampsPtr = r[2];
            MemoryUtil.memSet(stampsPtr, 0, (long) MAX_NODES * 8);
            requestsBuf = new long[frames]; requestsAlloc = new long[frames]; requestsPtr = new long[frames];
            rootsBuf = new long[frames]; rootsAlloc = new long[frames]; rootsPtr = new long[frames];
            for (int i = 0; i < frames; i++) {
                r = host(stack, 4 + MAX_REQUESTS * 4L);
                requestsBuf[i] = r[0]; requestsAlloc[i] = r[1]; requestsPtr[i] = r[2];
                MemoryUtil.memPutInt(requestsPtr[i], 0);
                r = host(stack, MAX_ROOTS * 4L);
                rootsBuf[i] = r[0]; rootsAlloc[i] = r[1]; rootsPtr[i] = r[2];
            }
            long[] d = device(stack, QUEUE * 2L * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            queueBuf = d[0]; queueAlloc = d[1];
            d = device(stack, 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            travBuf = d[0]; travAlloc = d[1];
            d = device(stack, SORT_MAX * 8L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            sortBuf = d[0]; sortAlloc = d[1];
            // 64 bucket counts, 64 fill counters, then (slot, bucket) per opaque candidate.
            d = device(stack, 512 + MAX_CANDIDATES * 8L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            orderBuf = d[0]; orderAlloc = d[1];
        }
        sets = new long[frames];
        createSets();
    }

    private static long[] host(MemoryStack stack, long size) {
        LongBuffer pb = stack.mallocLong(1);
        PointerBuffer pa = stack.mallocPointer(1);
        MemoryManager.createBufferHostAccess(size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, pb, pa);
        return new long[]{pb.get(0), pa.get(0), MemoryManager.Map(pa.get(0)).get(0)};
    }

    private static long[] device(MemoryStack stack, long size, int usage) {
        LongBuffer pb = stack.mallocLong(1);
        PointerBuffer pa = stack.mallocPointer(1);
        MemoryManager.createBuffer(size, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pb, pa);
        return new long[]{pb.get(0), pa.get(0)};
    }

    private void createSets() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p),
                    "traversal set layout");
            setLayout = p.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(frames * BINDINGS);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(frames).pPoolSizes(sizes),
                    null, p), "traversal pool");
            pool = p.get(0);
            LongBuffer layouts = stack.mallocLong(frames);
            for (int i = 0; i < frames; i++) layouts.put(i, setLayout);
            LongBuffer out = stack.mallocLong(frames);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "traversal sets");
            for (int i = 0; i < frames; i++) {
                sets[i] = out.get(i);
                long[] bufs = {nodes.buffer(i), children.buffer(i), stampsBuf, requestsBuf[i], rootsBuf[i], queueBuf, travBuf, sortBuf, orderBuf};
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(BINDINGS, stack);
                for (int k = 0; k < BINDINGS; k++) {
                    w.get(k).sType$Default().dstSet(sets[i]).dstBinding(k).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                            .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(bufs[k]).offset(0).range(VK_WHOLE_SIZE));
                }
                vkUpdateDescriptorSets(device, w, null);
            }
        }
    }

    private void ensurePipelines(long mainLayout) {
        if (traverse != null && mainLayout == mainSetLayout) return;
        destroyPipelines();
        mainSetLayout = mainLayout;
        long[] layouts = {mainLayout, setLayout};
        traverse = new net.vesu.vulkan.ComputePipeline(compile("lod/traverse.comp"), layouts, 32);
        args = new net.vesu.vulkan.ComputePipeline(compile("lod/travargs.comp"), layouts, 8);
        sort = new net.vesu.vulkan.ComputePipeline(compile("lod/tsort.comp"), layouts, 0);
        order = new net.vesu.vulkan.ComputePipeline(compile("lod/osort.comp"), layouts, 0);
    }

    private static ShaderSPIRVUtils.SPIRV compile(String path) {
        ShaderSPIRVUtils.SPIRV s = ShaderSPIRVUtils.compileShaderFile(path, ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        if (s == null) throw new IllegalStateException("LOD traversal shader failed to compile: " + path);
        return s;
    }

    private void destroyPipelines() {
        net.vesu.vulkan.ComputePipeline[] old = {traverse, args, sort, order};
        traverse = args = sort = order = null;
        net.vesu.vulkan.memory.MemoryManager.addToFreeable(() -> {
            for (net.vesu.vulkan.ComputePipeline p : old) if (p != null) p.cleanUp();
        });
    }

    // ---- The table ----

    /**
     * Once per frame, before any table change: frame slot {@code frameSlot} takes the changes
     * made since it last ran, and what no frame in flight can read is released.
     */
    public void beginFrame(int frameSlot) {
        nodes.beginFrame(frameSlot);
        children.beginFrame(frameSlot);
        frameNo++;
        while (!pending.isEmpty() && pending.peek()[0] <= frameNo) {
            long[] f = pending.poll();
            if (f[1] == 0) freeIds[freeIdCount++] = (int) f[2];
            else freeBlocks[freeBlockCount++] = (int) f[2];
        }
    }

    /** A new node id (0 when the table is full). */
    public int allocNode() {
        if (freeIdCount > 0) return freeIds[--freeIdCount];
        return nextId < MAX_NODES ? nextId++ : 0;
    }

    public void freeNode(int id) {
        if (id == 0) return;
        writeNode(id, 0, NO_SLOT, 0, false, false, 0, 0, 0, 0);
        MemoryUtil.memPutLong(stampsPtr + id * 8L, 0L);
        pending.add(new long[]{frameNo + frames + 1, 0, id});
    }

    /** A block of 8 child ids, all empty (0 when none is left). */
    public int allocBlock() {
        int b = freeBlockCount > 0 ? freeBlocks[--freeBlockCount] : nextBlock < MAX_BLOCKS ? nextBlock++ : 0;
        if (b != 0) {
            MemoryUtil.memSet(children.at(b), 0, 32);
            children.changed(b);
        }
        return b;
    }

    public void freeBlock(int block) {
        if (block == 0) return;
        pending.add(new long[]{frameNo + frames + 1, 1, block});
    }

    public void setChild(int block, int octant, int id) {
        if (block == 0) return;
        MemoryUtil.memPutInt(children.at(block) + octant * 4L, id);
        children.changed(block);
    }

    public void writeNode(int id, int block, int slot, int childMask, boolean ready, boolean translucent, int lvl, int x, int y, int z) {
        if (id == 0) return;
        long p = nodes.at(id);
        MemoryUtil.memPutInt(p, block);
        MemoryUtil.memPutInt(p + 4, slot);
        MemoryUtil.memPutInt(p + 8, (childMask & 0xFF) | (ready ? 0x100 : 0) | (translucent ? 0x200 : 0) | lvl << 12);
        MemoryUtil.memPutInt(p + 12, 0);
        MemoryUtil.memPutInt(p + 16, x);
        MemoryUtil.memPutInt(p + 20, y);
        MemoryUtil.memPutInt(p + 24, z);
        MemoryUtil.memPutInt(p + 28, 0);
        nodes.changed(id);
    }

    /** The last frame the traversal visited the node (0: never). */
    public int visited(int id) {
        return id == 0 ? 0 : MemoryUtil.memGetInt(stampsPtr + id * 8L);
    }

    /** The last frame the traversal drew the node (0: never). */
    public int drawn(int id) {
        return id == 0 ? 0 : MemoryUtil.memGetInt(stampsPtr + id * 8L + 4);
    }

    /**
     * The requests of the frame that last used this frame slot (done: its fence was waited
     * on), then clears them for this frame. Returns the number written into {@code out}.
     */
    public int takeRequests(int frameSlot, int[] out) {
        long p = requestsPtr[frameSlot];
        int n = Math.min(MemoryUtil.memGetInt(p), Math.min(MAX_REQUESTS, out.length));
        for (int i = 0; i < n; i++) out[i] = MemoryUtil.memGetInt(p + 4 + i * 4L);
        MemoryUtil.memPutInt(p, 0);
        return n;
    }

    public int maxRoots() {
        return MAX_ROOTS;
    }

    public void setRoot(int frameSlot, int i, int id) {
        MemoryUtil.memPutInt(rootsPtr[frameSlot] + i * 4L, id);
    }

    // ---- Recording ----

    /**
     * Outside rendering, before the cull pass: clears the counters, walks levels 4..0, puts
     * the opaque candidates near to far (at most {@code candidateBound} of them) and sorts the
     * translucent ones. {@code mainSet} is the LOD renderer's set of this frame (uniforms,
     * coverage, candidates, counts).
     */
    public void record(VkCommandBuffer cmd, int frameSlot, long mainLayout, long mainSet, long countBuffer, int rootCount,
                       int candidateBound, int frameId, float pixelScale, float quality, float lodBlocks, int requestBudget) {
        ensurePipelines(mainLayout);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdFillBuffer(cmd, travBuf, 0, 32, 0);
            vkCmdFillBuffer(cmd, countBuffer, 16, 8, 0);
            vkCmdFillBuffer(cmd, orderBuf, 0, 512, 0);
            barrier(cmd, stack, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            LongBuffer bound = stack.longs(mainSet, sets[frameSlot]);
            ByteBuffer pc = stack.malloc(32);
            pc.putInt(0, 0).putInt(4, rootCount).putInt(8, frameId).putInt(12, Math.min(requestBudget, MAX_REQUESTS))
                    .putFloat(16, pixelScale).putFloat(20, quality).putFloat(24, lodBlocks * lodBlocks).putInt(28, 0);
            traverse.bind(cmd);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, traverse.layout(), 0, bound, null);
            vkCmdPushConstants(cmd, traverse.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            if (rootCount > 0) vkCmdDispatch(cmd, (rootCount + 63) / 64, 1, 1);
            for (int lvl = 3; lvl >= 0; lvl--) {
                // Level 4 filled queue 0, level 3 queue 1, and so on.
                int read = (3 - lvl) & 1, write = read ^ 1;
                barrier(cmd, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                args.bind(cmd);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, args.layout(), 0, bound, null);
                vkCmdPushConstants(cmd, args.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(read, write));
                vkCmdDispatch(cmd, 1, 1, 1);
                barrier(cmd, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT);
                traverse.bind(cmd);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, traverse.layout(), 0, bound, null);
                pc.putInt(0, 1 + read);
                vkCmdPushConstants(cmd, traverse.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
                vkCmdDispatchIndirect(cmd, travBuf, 0);
            }
            barrier(cmd, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            if (candidateBound > 0) {
                order.bind(cmd);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, order.layout(), 0, bound, null);
                vkCmdDispatch(cmd, (Math.min(candidateBound, MAX_CANDIDATES) + 63) / 64, 1, 1);
            }
            sort.bind(cmd);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, sort.layout(), 0, bound, null);
            vkCmdDispatch(cmd, 1, 1, 1);
            barrier(cmd, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack, int dstStages) {
        VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, dstStages, 0, mb, null, null);
    }

    public void destroy() {
        destroyPipelines();
        nodes.destroy();
        children.destroy();
        long[] host = {stampsBuf, stampsAlloc, queueBuf, queueAlloc,
                travBuf, travAlloc, sortBuf, sortAlloc, orderBuf, orderAlloc};
        long[][] perFrame = {requestsBuf, requestsAlloc, rootsBuf, rootsAlloc};
        long layout = setLayout, p = pool;
        net.vesu.vulkan.memory.MemoryManager.addToFreeable(() -> {
            for (int i = 0; i < host.length; i += 2) MemoryManager.freeBuffer(host[i], host[i + 1]);
            for (int i = 0; i < perFrame[0].length; i++) {
                MemoryManager.freeBuffer(perFrame[0][i], perFrame[1][i]);
                MemoryManager.freeBuffer(perFrame[2][i], perFrame[3][i]);
            }
            vkDestroyDescriptorPool(Vulkan.getDevice(), p, null);
            vkDestroyDescriptorSetLayout(Vulkan.getDevice(), layout, null);
        });
    }

    private static void check(int rc, String what) {
        if (rc != VK_SUCCESS) throw new IllegalStateException(what + ": " + rc);
    }
}
