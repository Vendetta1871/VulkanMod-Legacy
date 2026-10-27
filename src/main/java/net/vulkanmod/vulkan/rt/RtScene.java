package net.vulkanmod.vulkan.rt;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.CameraHistory;
import net.vulkanmod.render.ChunkBatcher;
import net.vulkanmod.vulkan.DeviceFeatures;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;

/**
 * Ray-tracing scene (T3/T4): one BLAS per chunk section and render layer, built from the
 * shared chunk arena, and a TLAS rebuilt every frame with one instance per section placed
 * at {@code origin - camera} (the same camera-relative space the raster path uses).
 *
 * <p>Sections enter the TLAS once they have been drawn at least once (that is when their
 * origin is known). Translucent sections are left out for now.</p>
 */
public final class RtScene {

    /** Ray mask bits per render layer. */
    public static final int MASK_OPAQUE = 0x01, MASK_CUTOUT = 0x02;

    private static final int MAX_BUILDS_PER_FRAME = 256;
    private static final int INSTANCE_BYTES = 64;
    private static final int INSTANCE_RING = 4;

    /** VULKANMOD_RT=1 turns the RT scene on (also the rt debug view). */
    private static final boolean REQUESTED = System.getenv("VULKANMOD_RT") != null
            || "rt".equalsIgnoreCase(System.getenv("VULKANMOD_DEBUG_VIEW"));

    private static Boolean enabled;

    /** Per-section state, owned by the section's VBO. */
    public static final class Section {
        int index = -1;
        int arenaOffset, vertexCount;
        int originX, originY, originZ;
        boolean placed, dirty;
        int mask;
        long blas;
        RtBuffer blasBuffer;
        long blasAddress;
    }

    private static final ArrayList<Section> sections = new ArrayList<>();
    private static final ArrayList<Section> buildQueue = new ArrayList<>();

    private static RtBuffer scratch;
    private static RtBuffer[] instanceBuffers = new RtBuffer[INSTANCE_RING];
    private static int instanceRingIndex;

    private static long tlas;
    private static RtBuffer tlasBuffer;
    private static int tlasCapacity;
    private static int tlasInstanceCount;
    private static boolean tlasBuilt;

    private RtScene() {}

    public static boolean enabled() {
        if (enabled == null) {
            DeviceFeatures f = Vulkan.getDeviceInfo().features;
            enabled = REQUESTED && f != null && f.rayTracingPipeline && TerrainBatch.active();
            if (REQUESTED && !enabled) {
                Initializer.LOGGER.warn("[rt] requested but unavailable (needs ray-tracing pipeline support and the batched chunk path)");
            }
        }
        return enabled;
    }

    // --- section lifecycle (called from VBO) ---

    /** New geometry for a section: its BLAS is (re)built on a later frame. */
    public static void onUpload(Section s, int arenaOffset, int vertexCount) {
        releaseBlas(s);
        s.arenaOffset = arenaOffset;
        s.vertexCount = vertexCount;
        if (vertexCount <= 0) {
            remove(s);
            return;
        }
        if (s.index < 0) {
            s.index = sections.size();
            sections.add(s);
        }
        if (!s.dirty) {
            s.dirty = true;
            buildQueue.add(s);
        }
    }

    /** The section was drawn at this origin in this layer. */
    public static void onDraw(Section s, int x, int y, int z, int mask) {
        s.originX = x;
        s.originY = y;
        s.originZ = z;
        s.mask = mask;
        s.placed = true;
    }

    public static void onClose(Section s) {
        releaseBlas(s);
        remove(s);
    }

    /** Every section is gone (world change); the frames using them were waited for. */
    public static void onArenaReset() {
        for (Section s : sections) {
            releaseBlas(s);
            s.index = -1;
            s.dirty = false;
            s.placed = false;
        }
        sections.clear();
        buildQueue.clear();
    }

    private static void remove(Section s) {
        if (s.index >= 0) {
            Section last = sections.remove(sections.size() - 1);
            if (last != s) {
                sections.set(s.index, last);
                last.index = s.index;
            }
            s.index = -1;
        }
        s.placed = false;
        if (s.dirty) {
            buildQueue.remove(s);
            s.dirty = false;
        }
    }

    private static void releaseBlas(Section s) {
        if (s.blas != 0L) {
            long handle = s.blas;
            RtBuffer buffer = s.blasBuffer;
            RtBuffer.defer(() -> {
                vkDestroyAccelerationStructureKHR(Vulkan.getDevice(), handle, null);
                buffer.destroyNow();
            });
            s.blas = 0L;
            s.blasBuffer = null;
            s.blasAddress = 0L;
        }
    }

    // --- per frame ---

    public static long tlas() { return tlas; }
    public static boolean ready() { return tlasBuilt; }
    public static int instanceCount() { return tlasInstanceCount; }

    /**
     * Records BLAS builds for queued sections and the TLAS build. Runs outside rendering,
     * after the scene phase; the frame's arena uploads were submitted before this command
     * buffer (same submit), the barrier below makes them visible to the builds.
     */
    public static void record(VkCommandBuffer cmd, MemoryStack stack) {
        RtBuffer.tick();

        VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack);
        pre.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR
                        | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR)
                .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR
                        | VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre));

        if (buildBlases(cmd)) {
            // The TLAS build reads the new BLASes and reuses the scratch buffer.
            memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        }
        buildTlas(cmd);
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    private static void memoryBarrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess,
                                      long dstStage, long dstAccess) {
        VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    /** Builds up to MAX_BUILDS_PER_FRAME queued, placed BLASes in one call. */
    private static boolean buildBlases(VkCommandBuffer cmd) {
        if (buildQueue.isEmpty()) return false;
        DeviceFeatures f = Vulkan.getDeviceInfo().features;
        long arenaAddress = RtBuffer.addressOf(ChunkBatcher.arena().bufferId());

        try (MemoryStack stack = stackPush()) {
            int count = 0;
            ArrayList<Section> batch = new ArrayList<>();
            for (int i = 0; i < buildQueue.size() && count < MAX_BUILDS_PER_FRAME; i++) {
                Section s = buildQueue.get(i);
                if (s.placed) {
                    batch.add(s);
                    count++;
                }
            }
            if (batch.isEmpty()) return false;

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(count, stack);
            PointerBuffer ranges = stack.mallocPointer(count);
            long[] scratchOffsets = new long[count];
            long scratchTotal = 0;
            long scratchAlign = Math.max(1, f.minAccelerationStructureScratchOffsetAlignment);

            for (int i = 0; i < count; i++) {
                Section s = batch.get(i);
                IndexBuffer ib = Drawer.getInstance().getQuadsIndexBuffer().getIndexBuffer(s.vertexCount);
                boolean shortIndices = ib.indexType == IndexBuffer.IndexType.SHORT;
                int primitives = s.vertexCount / 4 * 2;

                VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
                geometry.get(0).sType$Default()
                        .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        // Cutout layers are treated as opaque until any-hit alpha testing exists.
                        .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
                geometry.get(0).geometry().triangles().sType$Default()
                        .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                        .vertexStride(ChunkBatcher.STRIDE)
                        .maxVertex(s.vertexCount - 1)
                        .indexType(shortIndices ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
                geometry.get(0).geometry().triangles().vertexData().deviceAddress(arenaAddress + s.arenaOffset);
                geometry.get(0).geometry().triangles().indexData().deviceAddress(RtBuffer.addressOf(ib.getId()));

                VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(i).sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .geometryCount(1)
                    .pGeometries(geometry);

                VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                vkGetAccelerationStructureBuildSizesKHR(Vulkan.getDevice(),
                        VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, stack.ints(primitives), sizes);

                s.blasBuffer = RtBuffer.create(sizes.accelerationStructureSize(),
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
                LongBuffer pAs = stack.mallocLong(1);
                int rc = vkCreateAccelerationStructureKHR(Vulkan.getDevice(),
                        VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                                .buffer(s.blasBuffer.buffer)
                                .size(sizes.accelerationStructureSize())
                                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, pAs);
                if (rc != VK_SUCCESS) throw new RuntimeException("vkCreateAccelerationStructureKHR (BLAS): " + rc);
                s.blas = pAs.get(0);
                s.blasAddress = vkGetAccelerationStructureDeviceAddressKHR(Vulkan.getDevice(),
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default()
                                .accelerationStructure(s.blas));
                info.dstAccelerationStructure(s.blas);

                scratchOffsets[i] = scratchTotal;
                scratchTotal += align(sizes.buildScratchSize(), scratchAlign);

                VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
                range.get(0).primitiveCount(primitives);
                ranges.put(i, range.address());
            }

            ensureScratch(scratchTotal + scratchAlign);
            long scratchBase = align(scratch.address, scratchAlign);
            for (int i = 0; i < count; i++) {
                infos.get(i).scratchData().deviceAddress(scratchBase + scratchOffsets[i]);
            }
            vkCmdBuildAccelerationStructuresKHR(cmd, infos, ranges);

            for (Section s : batch) s.dirty = false;
            buildQueue.removeAll(batch);
            return true;
        }
    }

    private static void ensureScratch(long bytes) {
        if (scratch != null && scratch.size >= bytes) return;
        if (scratch != null) {
            RtBuffer old = scratch;
            RtBuffer.defer(old::destroyNow);
        }
        long size = Math.max(bytes, scratch == null ? 32L << 20 : scratch.size * 2);
        scratch = RtBuffer.create(size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
    }

    private static void buildTlas(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            // Instances: every placed section with a BLAS, translated to camera-relative space.
            int count = 0;
            for (Section s : sections) if (s.blas != 0L && s.placed && s.mask != 0) count++;

            instanceRingIndex = (instanceRingIndex + 1) % INSTANCE_RING;
            RtBuffer instances = instanceBuffers[instanceRingIndex];
            long needed = (long) Math.max(1, count) * INSTANCE_BYTES;
            if (instances == null || instances.size < needed) {
                if (instances != null) {
                    RtBuffer old = instances;
                    RtBuffer.defer(old::destroyNow);
                }
                instances = RtBuffer.create(Math.max(needed, 4096L * INSTANCE_BYTES) * 2,
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
                instanceBuffers[instanceRingIndex] = instances;
            }

            double cx = CameraHistory.camX(), cy = CameraHistory.camY(), cz = CameraHistory.camZ();
            ByteBuffer m = instances.mapped;
            int n = 0;
            for (Section s : sections) {
                if (s.blas == 0L || !s.placed || s.mask == 0) continue;
                int o = n * INSTANCE_BYTES;
                // 3x4 row-major transform: identity rotation, translation origin - camera.
                m.putFloat(o, 1f).putFloat(o + 4, 0f).putFloat(o + 8, 0f).putFloat(o + 12, (float) (s.originX - cx));
                m.putFloat(o + 16, 0f).putFloat(o + 20, 1f).putFloat(o + 24, 0f).putFloat(o + 28, (float) (s.originY - cy));
                m.putFloat(o + 32, 0f).putFloat(o + 36, 0f).putFloat(o + 40, 1f).putFloat(o + 44, (float) (s.originZ - cz));
                m.putInt(o + 48, (s.index & 0xFFFFFF) | (s.mask << 24));
                // SBT offset 0; the geometry is flagged opaque already.
                m.putInt(o + 52, 0);
                m.putLong(o + 56, s.blasAddress);
                n++;
            }
            tlasInstanceCount = n;

            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
            geometry.get(0).geometry().instances().data().deviceAddress(instances.address);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(1)
                    .pGeometries(geometry);

            ensureTlas(Math.max(1, n), info.get(0), stack);

            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(Vulkan.getDevice(), VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    info.get(0), stack.ints(tlasCapacity), sizes);
            long scratchAlign = Math.max(1, Vulkan.getDeviceInfo().features.minAccelerationStructureScratchOffsetAlignment);
            ensureScratch(sizes.buildScratchSize() + scratchAlign);

            info.get(0).dstAccelerationStructure(tlas);
            info.get(0).scratchData().deviceAddress(align(scratch.address, scratchAlign));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(n);
            vkCmdBuildAccelerationStructuresKHR(cmd, info, stack.pointers(range.address()));
            tlasBuilt = true;
        }
    }

    /** (Re)creates the TLAS when the instance count outgrows it; the old one is released later. */
    private static void ensureTlas(int instances, VkAccelerationStructureBuildGeometryInfoKHR info, MemoryStack stack) {
        if (tlas != 0L && instances <= tlasCapacity) return;
        if (tlas != 0L) {
            long handle = tlas;
            RtBuffer buffer = tlasBuffer;
            RtBuffer.defer(() -> {
                vkDestroyAccelerationStructureKHR(Vulkan.getDevice(), handle, null);
                buffer.destroyNow();
            });
        }
        tlasCapacity = Math.max(instances * 2, 4096);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(Vulkan.getDevice(), VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                info, stack.ints(tlasCapacity), sizes);
        tlasBuffer = RtBuffer.create(sizes.accelerationStructureSize(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
        LongBuffer pAs = stack.mallocLong(1);
        int rc = vkCreateAccelerationStructureKHR(Vulkan.getDevice(),
                VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                        .buffer(tlasBuffer.buffer)
                        .size(sizes.accelerationStructureSize())
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR), null, pAs);
        if (rc != VK_SUCCESS) throw new RuntimeException("vkCreateAccelerationStructureKHR (TLAS): " + rc);
        tlas = pAs.get(0);
        tlasGeneration++;
    }

    private static int tlasGeneration;

    /** Bumped when the TLAS handle changes; descriptor users compare it. */
    public static int tlasGeneration() { return tlasGeneration; }

    private static long align(long v, long a) {
        return (v + a - 1) / a * a;
    }

    /** Releases everything; the device must be idle. */
    public static void destroy() {
        for (Section s : sections) releaseBlas(s);
        sections.clear();
        buildQueue.clear();
        if (tlas != 0L) {
            vkDestroyAccelerationStructureKHR(Vulkan.getDevice(), tlas, null);
            tlasBuffer.destroyNow();
            tlas = 0L;
        }
        if (scratch != null) { scratch.destroyNow(); scratch = null; }
        for (int i = 0; i < INSTANCE_RING; i++) {
            if (instanceBuffers[i] != null) { instanceBuffers[i].destroyNow(); instanceBuffers[i] = null; }
        }
        RtBuffer.flushAll();
        tlasBuilt = false;
    }

    /** The batched chunk path is required: BLAS geometry lives in its arena. */
    private static final class TerrainBatch {
        static boolean active() {
            return net.vulkanmod.render.TerrainRenderer.BATCH;
        }
    }
}
