package net.vesu.vulkan.rt;

import net.vesu.Initializer;
import net.vesu.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * BLAS compaction (R10). Section BLASes are built with ALLOW_COMPACTION; right after the
 * build their compacted size goes into a query. Once no frame in flight can still be
 * writing it, the size is read back and the BLAS is copied into a buffer of that size;
 * the original is released like any other BLAS. A section rebuilt or released meanwhile
 * just drops its query.
 */
final class RtCompaction {

    private static final int QUERIES = 2048, MAX_COPIES_PER_FRAME = 128;
    /** Below this saving the copy is not worth its build-stage time. */
    private static final double MIN_SAVING = 0.10;

    /** A BLAS owner: a vanilla section or a LOD node. */
    interface Target {
        long blas();

        RtBuffer blasBuffer();

        /** The compacted copy replaces the BLAS (the original is released by the caller). */
        void compacted(long blas, RtBuffer buffer, long address);
    }

    private record Pending(Target section, long blas, int query, long readyFrame) {}

    private static long pool;
    private static final int[] freeQueries = new int[QUERIES];
    private static int freeTop;
    private static final ArrayList<Pending> built = new ArrayList<>();
    private static final ArrayDeque<Pending> waiting = new ArrayDeque<>();
    private static long bytesBefore, bytesAfter;

    private RtCompaction() {}

    private static void ensurePool() {
        if (pool != 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            int rc = vkCreateQueryPool(Vulkan.getDevice(), VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(QUERIES), null, p);
            if (rc != VK_SUCCESS) throw new RuntimeException("vkCreateQueryPool (BLAS compaction): " + rc);
            pool = p.get(0);
        }
        for (int i = 0; i < QUERIES; i++) freeQueries[i] = QUERIES - 1 - i;
        freeTop = QUERIES;
    }

    /** A section's BLAS was just recorded for building; its size is queried after the build barrier. */
    static void onBuilt(Target s) {
        ensurePool();
        if (freeTop == 0) return;
        built.add(new Pending(s, s.blas(), freeQueries[--freeTop], RtBuffer.frame() + RtBuffer.delayFrames()));
    }

    /** After the barrier that ends the BLAS builds: writes this frame's compacted-size queries. */
    static void writeQueries(VkCommandBuffer cmd) {
        if (built.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (Pending p : built) {
                vkCmdResetQueryPool(cmd, pool, p.query, 1);
                vkCmdWriteAccelerationStructuresPropertiesKHR(cmd, stack.longs(p.blas),
                        VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, pool, p.query);
                waiting.add(p);
            }
        }
        built.clear();
    }

    /**
     * Before the builds: copies BLASes whose sizes are known into compacted ones. Returns
     * whether any copy was recorded (the TLAS build must then wait for it).
     */
    static boolean compact(VkCommandBuffer cmd) {
        if (waiting.isEmpty()) return false;
        int copies = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(2);
            while (!waiting.isEmpty() && copies < MAX_COPIES_PER_FRAME
                    && waiting.peek().readyFrame <= RtBuffer.frame()) {
                Pending p = waiting.peek();
                Target s = p.section;
                if (s.blas() != p.blas) {
                    waiting.poll();
                    freeQueries[freeTop++] = p.query;
                    continue;
                }
                int rc = vkGetQueryPoolResults(Vulkan.getDevice(), pool, p.query, 1, result, 16,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if (rc == VK_NOT_READY || result.get(1) == 0) break;
                waiting.poll();
                freeQueries[freeTop++] = p.query;
                long size = result.get(0);
                if (rc != VK_SUCCESS || size <= 0 || size > s.blasBuffer().size * (1.0 - MIN_SAVING)) continue;

                RtBuffer buffer = RtBuffer.create(size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
                LongBuffer pAs = stack.mallocLong(1);
                rc = vkCreateAccelerationStructureKHR(Vulkan.getDevice(),
                        VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                                .buffer(buffer.buffer).size(size)
                                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, pAs);
                if (rc != VK_SUCCESS) {
                    buffer.destroyNow();
                    continue;
                }
                long compactedAs = pAs.get(0);
                vkCmdCopyAccelerationStructureKHR(cmd, VkCopyAccelerationStructureInfoKHR.calloc(stack).sType$Default()
                        .src(s.blas()).dst(compactedAs).mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR));

                long oldAs = s.blas();
                RtBuffer oldBuffer = s.blasBuffer();
                bytesBefore += oldBuffer.size;
                bytesAfter += size;
                // This frame's copy and earlier frames' traces still read the original.
                RtBuffer.defer(() -> {
                    vkDestroyAccelerationStructureKHR(Vulkan.getDevice(), oldAs, null);
                    oldBuffer.destroyNow();
                });
                s.compacted(compactedAs, buffer, vkGetAccelerationStructureDeviceAddressKHR(Vulkan.getDevice(),
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default()
                                .accelerationStructure(compactedAs)));
                copies++;
            }
        }
        if (copies > 0 && RtScene.LOG) {
            Initializer.LOGGER.info("[rt] compacted {} BLAS; so far {} KB -> {} KB", copies,
                    bytesBefore >> 10, bytesAfter >> 10);
        }
        return copies > 0;
    }

    static void destroy() {
        built.clear();
        waiting.clear();
        if (pool != 0L) {
            vkDestroyQueryPool(Vulkan.getDevice(), pool, null);
            pool = 0L;
        }
        bytesBefore = bytesAfter = 0;
    }
}
