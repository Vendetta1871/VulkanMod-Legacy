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
    public static final int MASK_OPAQUE = 0x01, MASK_CUTOUT = 0x02, MASK_ENTITY = 0x04, MASK_TRANSLUCENT = 0x08;
    /** Rays that cast shadows: everything but translucent (water, glass). */
    public static final int MASK_SHADOW = MASK_OPAQUE | MASK_CUTOUT | MASK_ENTITY;

    private static final int MAX_BUILDS_PER_FRAME = 256;
    private static final int INSTANCE_BYTES = 64;
    private static final int INSTANCE_RING = 4;

    /** The rt debug view needs the scene regardless of the setting. */
    private static final boolean DEBUG_VIEW = "rt".equalsIgnoreCase(System.getenv("VULKANMOD_DEBUG_VIEW"));

    private static boolean enabled;
    private static boolean warned;
    /** VULKANMOD_RT_LOG=1: per-frame build statistics. */
    static final boolean LOG = System.getenv("VULKANMOD_RT_LOG") != null;

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
        /** Light-emitting blocks (see RtLights.scan), scanned with the BLAS. */
        int[] lights;

        /** Holds geometry in the scene (unplaced/closed sections are not re-added by a draw). */
        public boolean isRegistered() { return index >= 0; }
    }

    private static final ArrayList<Section> sections = new ArrayList<>();
    private static final ArrayList<Section> buildQueue = new ArrayList<>();

    private static RtBuffer scratch;
    private static RtBuffer[] instanceBuffers = new RtBuffer[INSTANCE_RING];
    /** Per TLAS instance (gl_InstanceID): device address of the section's vertices. */
    private static RtBuffer[] instanceDataBuffers = new RtBuffer[INSTANCE_RING];
    private static int instanceRingIndex;

    private static long tlas;
    private static RtBuffer tlasBuffer;
    private static int tlasCapacity;
    private static int tlasInstanceCount;
    private static boolean tlasBuilt;

    private RtScene() {}

    public static boolean enabled() {
        return enabled;
    }

    /** Sections farther than this (chunks, horizontal) stay raster-only; the debug view traces all. */
    public static int distanceChunks() {
        int d = Initializer.CONFIG.effectiveRtDistance();
        return DEBUG_VIEW ? Math.max(d, 64) : d;
    }

    // Camera chunk and range for this frame's range tests (set in record).
    private static int camChunkX, camChunkZ, rangeChunks;

    /** Chebyshev chunk distance from the camera chunk, like vanilla's render distance. */
    private static int chunkDistance(Section s) {
        return Math.max(Math.abs((s.originX >> 4) - camChunkX), Math.abs((s.originZ >> 4) - camChunkZ));
    }

    /** The device has the ray-tracing pipeline and the batched chunk path is on. */
    public static boolean supported() {
        DeviceFeatures f = Vulkan.getDeviceInfo() != null ? Vulkan.getDeviceInfo().features : null;
        return f != null && f.rayTracingPipeline && TerrainBatch.active();
    }

    /**
     * Applies the RT preset/distances (VULKANMOD_RT / VULKANMOD_RT_DISTANCE override). Turning it on recreates the frame
     * targets (the scene color needs STORAGE) and reloads the chunks so every section gets
     * its BLAS; turning it off waits for the GPU and releases the RT resources.
     * Client thread only.
     */
    public static void applySettings() {
        boolean want = Initializer.CONFIG.effectiveRayTracing() || DEBUG_VIEW;
        if (want && !supported()) {
            if (!warned) {
                warned = true;
                Initializer.LOGGER.warn("[rt] requested but unavailable (needs ray-tracing pipeline support and the batched chunk path)");
            }
            want = false;
        }
        if (want == enabled) return;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (!want) {
            vkDeviceWaitIdle(Vulkan.getDevice());
            net.vulkanmod.vulkan.FrameTargets.releaseRtPasses();
            destroy();
            enabled = false;
            Initializer.LOGGER.info("[rt] off");
            return;
        }
        enabled = true;
        Initializer.LOGGER.info("[rt] on");
        // The scene color needs STORAGE before the first RT frame records.
        net.vulkanmod.vulkan.FrameTargets.recreateIfCreated();
        // Sections uploaded while RT was off carry no RT state: rebuild them.
        if (mc.world != null && mc.renderGlobal != null) {
            mc.renderGlobal.loadRenderers();
        }
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
        if (s.lights != null) {
            s.lights = null;
            RtLights.markDirty();
        }
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

    /** This frame's instance data buffer (one uint64 vertex address per instance). */
    public static long instanceDataBuffer() { return instanceDataBuffers[instanceRingIndex].buffer; }
    public static long instanceDataSize() { return instanceDataBuffers[instanceRingIndex].size; }

    /**
     * Records BLAS builds for queued sections and the TLAS build. Runs outside rendering,
     * after the scene phase; the frame's arena uploads were submitted before this command
     * buffer (same submit), the barrier below makes them visible to the builds.
     */
    public static void record(VkCommandBuffer cmd, MemoryStack stack) {
        RtBuffer.tick();
        camChunkX = (int) Math.floor(CameraHistory.camX()) >> 4;
        camChunkZ = (int) Math.floor(CameraHistory.camZ()) >> 4;
        rangeChunks = distanceChunks();
        updateRange();
        breadcrumb(cmd, stack, 1);

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

        boolean built = buildBlases(cmd);
        RtEntities.build(cmd, stack);
        built |= RtEntities.ready();
        if (built) breadcrumb(cmd, stack, 2);
        if (built) {
            // The TLAS build reads the new BLASes and reuses the scratch buffer.
            memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        }
        buildTlas(cmd);
        RtLights.update(cmd, sections, CameraHistory.camX(), CameraHistory.camY(), CameraHistory.camZ());
        RtLights.updateDynamic(cmd, stack, CameraHistory.camX(), CameraHistory.camY(), CameraHistory.camZ());
        breadcrumb(cmd, stack, 3);
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    // --- GPU hang breadcrumbs (VULKANMOD_RT_LOG): the last value the GPU wrote shows
    // which RT step a hung frame is stuck in. ---

    private static RtBuffer crumbs;
    private static final Object CRUMB_LOCK = new Object();
    private static boolean watchdogStarted;
    private static long crumbFrame;

    /**
     * Writes (frame << 4 | step) once everything recorded before has finished. Steps:
     * 0 frame start, 1 RT start, 2 BLAS builds done, 3 TLAS build done, 4 RT passes done,
     * 5 frame end (swapchain copy done).
     */
    public static void breadcrumb(VkCommandBuffer cmd, MemoryStack stack, int step) {
        if (!LOG) return;
        if (crumbs == null) {
            synchronized (CRUMB_LOCK) {
                crumbs = RtBuffer.create(64, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            }
            if (!watchdogStarted) {
                watchdogStarted = true;
                Thread watchdog = new Thread(RtScene::watchdog, "RT hang watchdog");
                watchdog.setDaemon(true);
                watchdog.start();
            }
        }
        if (step == 0) crumbFrame++;
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0);
        vkCmdFillBuffer(cmd, crumbs.buffer, 0, 4, (int) (crumbFrame << 4 | step));
    }

    private static void watchdog() {
        int last = -1;
        long since = System.currentTimeMillis();
        while (true) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            int v;
            synchronized (CRUMB_LOCK) {
                if (crumbs == null) continue;
                v = crumbs.mapped.getInt(0);
            }
            if (v != last) {
                last = v;
                since = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - since > 10000) {
                Initializer.LOGGER.error("[rt] GPU stuck: last breadcrumb frame {} step {} (CPU recorded frame {})",
                        v >>> 4, v & 15, crumbFrame);
                since = System.currentTimeMillis();
            }
        }
    }

    private static void memoryBarrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess,
                                      long dstStage, long dstAccess) {
        VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    /** Builds up to MAX_BUILDS_PER_FRAME queued, placed BLASes in one call. */
    /**
     * A full batch needs ~350 bytes of structs per section, more than the thread's 64 KiB
     * stack (LWJGL reports overflow as OutOfMemoryError, which vanilla handles by unloading
     * the world mid-frame).
     */
    private static final MemoryStack BUILD_STACK = MemoryStack.create(512 * 1024);

    private static boolean buildBlases(VkCommandBuffer cmd) {
        if (buildQueue.isEmpty()) return false;
        DeviceFeatures f = Vulkan.getDeviceInfo().features;
        long arenaAddress = RtBuffer.addressOf(ChunkBatcher.arena().bufferId());

        try (MemoryStack stack = BUILD_STACK.push()) {
            int count = 0;
            ArrayList<Section> batch = new ArrayList<>();
            boolean dropped = false;
            for (int i = 0; i < buildQueue.size() && count < MAX_BUILDS_PER_FRAME; i++) {
                Section s = buildQueue.get(i);
                if (!s.placed) continue;
                if (chunkDistance(s) > rangeChunks) {
                    // Re-queued by updateRange once it comes back in range.
                    s.dirty = false;
                    dropped = true;
                    continue;
                }
                batch.add(s);
                count++;
            }
            if (dropped) buildQueue.removeIf(s -> !s.dirty);
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
                        // Cutout sections run the any-hit alpha test; once per primitive is enough.
                        .flags(s.mask == MASK_OPAQUE ? VK_GEOMETRY_OPAQUE_BIT_KHR
                                : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
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
            if (LOG) {
                int maxVerts = 0;
                for (Section s : batch) maxVerts = Math.max(maxVerts, s.vertexCount);
                Initializer.LOGGER.info("[rt] built {} BLAS (max {} verts, scratch {} KB, arena {} MB, queue left {})",
                        count, maxVerts, scratchTotal / 1024, ChunkBatcher.arena().capacity() >> 20,
                        buildQueue.size() - count);
            }

            for (Section s : batch) {
                s.dirty = false;
                s.lights = RtLights.scan(s);
            }
            RtLights.markDirty();
            buildQueue.removeAll(batch);
            return true;
        }
    }

    /**
     * Queues in-range sections without a BLAS and releases BLASes two chunks past the
     * range (the margin keeps a camera moving along the edge from rebuilding them).
     */
    private static void updateRange() {
        for (Section s : sections) {
            int d = chunkDistance(s);
            if (d <= rangeChunks) {
                if (s.blas == 0L && !s.dirty && s.placed && s.vertexCount > 0) {
                    s.dirty = true;
                    buildQueue.add(s);
                }
            } else if (d > rangeChunks + 2 && s.blas != 0L) {
                releaseBlas(s);
            }
        }
    }

    private static boolean inTlas(Section s) {
        return s.blas != 0L && s.placed && s.mask != 0 && chunkDistance(s) <= rangeChunks;
    }

    private static ByteBuffer cpuInstances, cpuInstanceData;

    private static ByteBuffer ensureCpu(ByteBuffer b, long bytes) {
        if (b != null && b.capacity() >= bytes) return b;
        if (b != null) org.lwjgl.system.MemoryUtil.memFree(b);
        return org.lwjgl.system.MemoryUtil.memAlloc((int) Math.max(bytes * 2, 4096L * INSTANCE_BYTES));
    }

    /** vkCmdUpdateBuffer in 64 KiB pieces (its per-call limit). */
    static void updateBuffer(VkCommandBuffer cmd, long buffer, ByteBuffer src, int bytes) {
        updateBuffer(cmd, buffer, 0, src, bytes);
    }

    /** vkCmdUpdateBuffer in 64 KiB pieces, writing {@code bytes} of {@code src} at {@code dstOffset}. */
    static void updateBuffer(VkCommandBuffer cmd, long buffer, long dstOffset, ByteBuffer src, int bytes) {
        for (int off = 0; off < bytes; off += 65536) {
            int len = Math.min(65536, bytes - off);
            ByteBuffer slice = org.lwjgl.system.MemoryUtil.memSlice(src, off, len);
            vkCmdUpdateBuffer(cmd, buffer, dstOffset + off, slice);
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
            for (Section s : sections) if (inTlas(s)) count++;
            boolean entities = RtEntities.ready();
            if (entities) count++;

            instanceRingIndex = (instanceRingIndex + 1) % INSTANCE_RING;
            RtBuffer instances = instanceBuffers[instanceRingIndex];
            long needed = (long) Math.max(1, count) * INSTANCE_BYTES;
            if (instances == null || instances.size < needed) {
                if (instances != null) {
                    RtBuffer old = instances;
                    RtBuffer.defer(old::destroyNow);
                }
                instances = RtBuffer.create(Math.max(needed, 4096L * INSTANCE_BYTES) * 2,
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
                instanceBuffers[instanceRingIndex] = instances;
            }

            RtBuffer data = instanceDataBuffers[instanceRingIndex];
            if (data == null || data.size < (long) Math.max(1, count) * 8) {
                if (data != null) {
                    RtBuffer old = data;
                    RtBuffer.defer(old::destroyNow);
                }
                data = RtBuffer.create(Math.max((long) count * 8, 4096L * 8) * 2,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
                instanceDataBuffers[instanceRingIndex] = data;
            }
            long arenaAddress = RtBuffer.addressOf(ChunkBatcher.arena().bufferId());

            double cx = CameraHistory.camX(), cy = CameraHistory.camY(), cz = CameraHistory.camZ();
            // Device-local buffers filled through the command buffer: the GPU never reads
            // these from system memory over the (narrow, eGPU) PCIe link.
            ByteBuffer m = cpuInstances = ensureCpu(cpuInstances, needed);
            ByteBuffer dataCpu = cpuInstanceData = ensureCpu(cpuInstanceData, (long) Math.max(1, count) * 8);
            int n = 0;
            for (Section s : sections) {
                if (!inTlas(s)) continue;
                int o = n * INSTANCE_BYTES;
                // 3x4 row-major transform: identity rotation, translation origin - camera.
                m.putFloat(o, 1f).putFloat(o + 4, 0f).putFloat(o + 8, 0f).putFloat(o + 12, (float) (s.originX - cx));
                m.putFloat(o + 16, 0f).putFloat(o + 20, 1f).putFloat(o + 24, 0f).putFloat(o + 28, (float) (s.originY - cy));
                m.putFloat(o + 32, 0f).putFloat(o + 36, 0f).putFloat(o + 40, 1f).putFloat(o + 44, (float) (s.originZ - cz));
                // Custom index: section index, plus the translucent flag the shadow any-hit reads.
                m.putInt(o + 48, (s.index & 0x7FFFFF) | (s.mask == MASK_TRANSLUCENT ? 0x800000 : 0) | (s.mask << 24));
                // SBT offset 0; the geometry is flagged opaque already.
                m.putInt(o + 52, 0);
                m.putLong(o + 56, s.blasAddress);
                dataCpu.putLong(n * 8, arenaAddress + s.arenaOffset);
                n++;
            }
            if (entities) {
                // Entity vertices are camera-relative already; identity transform.
                int o = n * INSTANCE_BYTES;
                m.putFloat(o, 1f).putFloat(o + 4, 0f).putFloat(o + 8, 0f).putFloat(o + 12, 0f);
                m.putFloat(o + 16, 0f).putFloat(o + 20, 1f).putFloat(o + 24, 0f).putFloat(o + 28, 0f);
                m.putFloat(o + 32, 0f).putFloat(o + 36, 0f).putFloat(o + 40, 1f).putFloat(o + 44, 0f);
                // Custom index: entity flag and the quad count (the hit shader finds the
                // positions after the per-quad data).
                m.putInt(o + 48, 0x400000 | (RtEntities.quadCount() & 0x3FFFFF) | MASK_ENTITY << 24);
                m.putInt(o + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
                m.putLong(o + 56, RtEntities.blasAddress());
                dataCpu.putLong(n * 8, RtEntities.dataAddress());
                n++;
            }
            tlasInstanceCount = n;
            if (n > 0) {
                updateBuffer(cmd, instances.buffer, m, n * INSTANCE_BYTES);
                updateBuffer(cmd, data.buffer, dataCpu, n * 8);
                memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                        VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            }

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
            long scratchAddress = align(scratch.address, scratchAlign);

            info.get(0).dstAccelerationStructure(tlas);
            info.get(0).scratchData().deviceAddress(scratchAddress);

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
        for (Section s : sections) {
            releaseBlas(s);
            s.index = -1;
            s.dirty = false;
            s.placed = false;
        }
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
            if (instanceDataBuffers[i] != null) { instanceDataBuffers[i].destroyNow(); instanceDataBuffers[i] = null; }
        }
        RtLights.destroy();
        RtEntities.destroy();
        synchronized (CRUMB_LOCK) {
            if (crumbs != null) { crumbs.destroyNow(); crumbs = null; }
        }
        RtBuffer.flushAll();
        tlasBuilt = false;
        if (LOG) Initializer.LOGGER.info("[rt] destroyed, {} RT buffers still alive", RtBuffer.live);
    }

    /** The batched chunk path is required: BLAS geometry lives in its arena. */
    private static final class TerrainBatch {
        static boolean active() {
            return net.vulkanmod.render.TerrainRenderer.BATCH;
        }
    }
}
