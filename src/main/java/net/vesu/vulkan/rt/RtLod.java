package net.vesu.vulkan.rt;

import net.vesu.Initializer;
import net.vesu.vulkan.DeviceFeatures;
import net.vesu.vulkan.Drawer;
import net.vesu.vulkan.VkCompat;
import net.vesu.vulkan.Vulkan;
import net.vesu.vulkan.memory.IndexBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Distant terrain in the RT scene (AUDIT 5.17): the LOD nodes of level 1 and up drawn in a
 * ring past the vanilla distance get a BLAS each, with an instance at {@code origin - camera}.
 * Near terrain then receives shadows, indirect light and reflections from far terrain.
 *
 * <p>A node's quads are expanded on the CPU into the entity layout of the hit shader (per
 * quad: color RGBA8, sky | block light << 8; then four positions per quad, node-relative),
 * so the instances carry {@code INSTANCE_ENTITY} and {@link RtScene#MASK_ENTITY}: shadow,
 * indirect and reflection rays see them, primary rays (terrain masks) do not. Translucent
 * quads (water) stay out. Builds are budgeted per frame; a node the LOD renderer stops
 * drawing is released after a while, and with the node.</p>
 */
public final class RtLod {

    /** Expanded quads per frame. */
    private static final int QUAD_BUDGET = 1 << 17;
    /** Frames a node stays in the scene after its last draw (turning around keeps the ring). */
    private static final int KEEP_FRAMES = 600;

    private static final class Entry {
        final long key;
        final int ox, oy, oz, scale;
        long[] quads;
        int first, count;
        RtBuffer data, blasBuffer;
        long blas, blasAddress;
        int builtQuads;
        long lastWanted;
        boolean queued;

        Entry(long key, int ox, int oy, int oz, int scale) {
            this.key = key;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.scale = scale;
        }
    }

    private static final HashMap<Long, Entry> entries = new HashMap<>();
    private static final ArrayList<Entry> queue = new ArrayList<>();
    private static long modelsPtr, tintsPtr;
    private static long frame;
    private static RtBuffer scratch;

    private RtLod() {}

    /** The LOD renderer's host tables: models (64 bytes each) and biome tints. */
    public static void setTables(long models, long tints) {
        modelsPtr = models;
        tintsPtr = tints;
    }

    /**
     * A node's new mesh while it is in the ring: {@code quads[first, first + count)} are its
     * opaque quads, at block origin (ox, oy, oz), one voxel {@code scale} blocks.
     */
    public static void offer(long key, int ox, int oy, int oz, int scale, long[] quads, int first, int count) {
        if (!RtScene.enabled()) return;
        release(key);
        if (count <= 0) return;
        Entry e = new Entry(key, ox, oy, oz, scale);
        e.quads = quads;
        e.first = first;
        e.count = count;
        e.lastWanted = frame;
        e.queued = true;
        entries.put(key, e);
        queue.add(e);
    }

    /** The node was drawn in the ring this frame; false when it has no data (a new mesh is needed). */
    public static boolean touch(long key) {
        Entry e = entries.get(key);
        if (e == null) return false;
        e.lastWanted = frame;
        return true;
    }

    /** The node lost its geometry. */
    public static void release(long key) {
        Entry e = entries.remove(key);
        if (e == null) return;
        if (e.queued) queue.remove(e);
        free(e);
    }

    private static void free(Entry e) {
        e.quads = null;
        long handle = e.blas;
        RtBuffer data = e.data, as = e.blasBuffer;
        e.blas = 0L;
        e.data = e.blasBuffer = null;
        if (handle == 0L && data == null && as == null) return;
        RtBuffer.defer(() -> {
            if (handle != 0L) vkDestroyAccelerationStructureKHR(Vulkan.getDevice(), handle, null);
            if (as != null) as.destroyNow();
            if (data != null) data.destroyNow();
        });
    }

    /** Instances ready for the TLAS. */
    static int readyCount() {
        int n = 0;
        for (Entry e : entries.values()) if (e.blas != 0L) n++;
        return n;
    }

    /** Writes the ready instances from instance {@code n} on; returns the next index. */
    static int writeInstances(ByteBuffer m, ByteBuffer data, int n, int instanceBytes, double cx, double cy, double cz) {
        for (Entry e : entries.values()) {
            if (e.blas == 0L) continue;
            int o = n * instanceBytes;
            m.putFloat(o, 1f).putFloat(o + 4, 0f).putFloat(o + 8, 0f).putFloat(o + 12, (float) (e.ox - cx));
            m.putFloat(o + 16, 0f).putFloat(o + 20, 1f).putFloat(o + 24, 0f).putFloat(o + 28, (float) (e.oy - cy));
            m.putFloat(o + 32, 0f).putFloat(o + 36, 0f).putFloat(o + 40, 1f).putFloat(o + 44, (float) (e.oz - cz));
            // The entity layout of the hit shader: flag and quad count, then the entity mask.
            m.putInt(o + 48, 0x400000 | (e.builtQuads & 0x3FFFFF) | RtScene.MASK_ENTITY << 24);
            m.putInt(o + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
            m.putLong(o + 56, e.blasAddress);
            data.putLong(n * 8, e.data.address);
            n++;
        }
        return n;
    }

    /**
     * Releases nodes no longer drawn and builds the queued ones within the budget. Outside
     * rendering, before the TLAS build; true when a BLAS was built.
     */
    static boolean build(VkCommandBuffer cmd, MemoryStack stack) {
        frame++;
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (frame - e.lastWanted <= KEEP_FRAMES) continue;
            it.remove();
            if (e.queued) queue.remove(e);
            free(e);
        }
        if (queue.isEmpty() || modelsPtr == 0L) return false;
        ArrayList<Entry> batch = new ArrayList<>();
        int quads = 0;
        while (!queue.isEmpty() && (batch.isEmpty() || quads + queue.get(0).count <= QUAD_BUDGET)) {
            Entry e = queue.remove(0);
            e.queued = false;
            batch.add(e);
            quads += e.count;
        }

        // Expand into a staging buffer, copied into each node's device-local buffer.
        long total = 0;
        for (Entry e : batch) total += (long) e.count * (8 + 48);
        RtBuffer staging = RtBuffer.create(total, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        long at = 0;
        long[] offsets = new long[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            Entry e = batch.get(i);
            offsets[i] = at;
            expand(e, MemoryUtil.memAddress(staging.mapped) + at);
            long bytes = (long) e.count * (8 + 48);
            e.data = RtBuffer.create(bytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                    | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(at).dstOffset(0).size(bytes);
            vkCmdCopyBuffer(cmd, staging.buffer, e.data.buffer, region);
            at += bytes;
            e.builtQuads = e.count;
            e.quads = null;
        }
        RtBuffer.defer(staging::destroyNow);

        VkMemoryBarrier2.Buffer up = VkMemoryBarrier2.calloc(1, stack);
        up.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VkCompat.pipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(up));

        DeviceFeatures f = Vulkan.getDeviceInfo().features;
        long scratchAlign = Math.max(1, f.minAccelerationStructureScratchOffsetAlignment);
        int count = batch.size();
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(count, stack);
        PointerBuffer ranges = stack.mallocPointer(count);
        long[] scratchOffsets = new long[count];
        long scratchTotal = 0;
        for (int i = 0; i < count; i++) {
            Entry e = batch.get(i);
            int vertices = e.builtQuads * 4;
            IndexBuffer ib = Drawer.getInstance().getQuadsIndexBuffer().getIndexBuffer(vertices);
            boolean shortIndices = ib.indexType == IndexBuffer.IndexType.SHORT;
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.get(0).geometry().triangles().sType$Default()
                    .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(12)
                    .maxVertex(vertices - 1)
                    .indexType(shortIndices ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
            geometry.get(0).geometry().triangles().vertexData().deviceAddress(e.data.address + (long) e.builtQuads * 8);
            geometry.get(0).geometry().triangles().indexData().deviceAddress(RtBuffer.addressOf(ib.getId()));
            VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(i).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(1)
                    .pGeometries(geometry);
            int primitives = e.builtQuads * 2;
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(Vulkan.getDevice(), VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    info, stack.ints(primitives), sizes);
            e.blasBuffer = RtBuffer.create(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
            LongBuffer pAs = stack.mallocLong(1);
            int rc = vkCreateAccelerationStructureKHR(Vulkan.getDevice(), VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(e.blasBuffer.buffer).size(sizes.accelerationStructureSize())
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, pAs);
            if (rc != VK_SUCCESS) throw new RuntimeException("vkCreateAccelerationStructureKHR (LOD): " + rc);
            e.blas = pAs.get(0);
            e.blasAddress = vkGetAccelerationStructureDeviceAddressKHR(Vulkan.getDevice(),
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(e.blas));
            info.dstAccelerationStructure(e.blas);
            scratchOffsets[i] = scratchTotal;
            scratchTotal += (sizes.buildScratchSize() + scratchAlign - 1) / scratchAlign * scratchAlign;
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(primitives);
            ranges.put(i, range.address());
        }
        if (scratch == null || scratch.size < scratchTotal + scratchAlign) {
            if (scratch != null) {
                RtBuffer old = scratch;
                RtBuffer.defer(old::destroyNow);
            }
            scratch = RtBuffer.create((scratchTotal + scratchAlign) * 3 / 2, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
        }
        long base = (scratch.address + scratchAlign - 1) / scratchAlign * scratchAlign;
        for (int i = 0; i < count; i++) infos.get(i).scratchData().deviceAddress(base + scratchOffsets[i]);
        vkCmdBuildAccelerationStructuresKHR(cmd, infos, ranges);
        if (RtScene.LOG) {
            Initializer.LOGGER.info("[rt] LOD: built {} BLAS ({} quads), {} in the scene, {} queued", count, quads,
                    entries.size(), queue.size());
        }
        return true;
    }

    private static final int[] CU = {0, 1, 1, 0}, CV = {0, 0, 1, 1};

    /** The quads of a node in the entity layout, as pack_lod.vsh expands them for the raster. */
    private static void expand(Entry e, long out) {
        long pos = out + (long) e.count * 8;
        for (int i = 0; i < e.count; i++) {
            long q = e.quads[e.first + i];
            int lo = (int) q, hi = (int) (q >>> 32);
            int face = lo & 7;
            int w = ((lo >>> 3) & 15) + 1, h = ((lo >>> 7) & 15) + 1;
            int z = (lo >>> 11) & 31, y = (lo >>> 16) & 31, x = (lo >>> 21) & 31;
            int model = (lo >>> 26) | (hi & 0x3FFF) << 6;
            long mp = modelsPtr + model * 64L;
            int fd = MemoryUtil.memGetInt(mp + face * 4L);
            float indent = ((fd >>> 16) & 63) / 64.0f;
            int axis = face >> 1;
            float plane = (face & 1) == 1 ? 1.0f - indent : indent;
            float s = e.scale;
            for (int k = 0; k < 4; k++) {
                float cu = CU[k] * w, cv = CV[k] * h;
                float px, py, pz;
                if (axis == 0) { px = x + cu; py = y + plane; pz = z + cv; }
                else if (axis == 1) { px = x + cu; py = y + cv; pz = z + plane; }
                else { px = x + plane; py = y + cv; pz = z + cu; }
                long p = pos + (i * 4L + k) * 12;
                MemoryUtil.memPutFloat(p, px * s);
                MemoryUtil.memPutFloat(p + 4, py * s);
                MemoryUtil.memPutFloat(p + 8, pz * s);
            }
            int c = MemoryUtil.memGetInt(mp + 36 + face * 4L);
            float r = (c & 255) / 255.0f, g = ((c >>> 8) & 255) / 255.0f, b = ((c >>> 16) & 255) / 255.0f;
            int flags = MemoryUtil.memGetInt(mp + 24);
            int tintMode = (fd >>> 24) & 3;
            if ((flags & 1) != 0 && tintMode != 0) {
                int biome = (hi >>> 14) & 0x1FF;
                int tint = MemoryUtil.memGetInt(mp + 28);
                int t = (flags & 2) != 0 ? MemoryUtil.memGetInt(tintsPtr + (long) (tint + biome) * 4) : tint;
                float tr = ((t >>> 16) & 255) / 255.0f, tg = ((t >>> 8) & 255) / 255.0f, tb = (t & 255) / 255.0f;
                // A partly tinted face (grass block sides) tints part of its average.
                if (tintMode != 2) {
                    tr = 0.5f + tr * 0.5f;
                    tg = 0.5f + tg * 0.5f;
                    tb = 0.5f + tb * 0.5f;
                }
                r *= tr;
                g *= tg;
                b *= tb;
            }
            int light = (hi >>> 23) & 0xFF;
            long o = out + i * 8L;
            MemoryUtil.memPutInt(o, (int) (r * 255) | (int) (g * 255) << 8 | (int) (b * 255) << 16 | 0xFF << 24);
            // Packed block << 4 | sky, as pack_lod.vsh reads it; the hit shader wants sky | block << 8.
            MemoryUtil.memPutInt(o + 4, (light & 15) | (light >> 4) << 8);
        }
    }

    /** The LOD session closed: every node leaves the scene. */
    public static void clear() {
        for (Entry e : entries.values()) free(e);
        entries.clear();
        queue.clear();
    }

    /** With the device idle (RtScene.destroy flushes the deferred releases after this). */
    static void destroy() {
        clear();
        if (scratch != null) {
            RtBuffer old = scratch;
            RtBuffer.defer(old::destroyNow);
            scratch = null;
        }
    }
}
