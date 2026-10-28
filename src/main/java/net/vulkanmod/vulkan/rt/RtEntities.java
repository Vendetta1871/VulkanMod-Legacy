package net.vulkanmod.vulkan.rt;

import net.vulkanmod.render.CameraHistory;
import net.vulkanmod.vulkan.DeviceFeatures;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Entity geometry for the RT scene (T5): the world-pass entity models of this frame
 * (mobs, players, dropped items, block-entity models) are captured at their draw,
 * moved into camera-relative world space and built into one BLAS per frame. Its TLAS
 * instance carries {@link RtScene#MASK_ENTITY}: shadow rays see it, primary rays (which
 * read chunk vertices in the hit shaders) do not.
 */
public final class RtEntities {

    private static final int MAX_VERTICES = 1 << 20;

    private static ByteBuffer cpu = MemoryUtil.memAlloc(64 * 1024 * 12);
    private static int vertexCount;
    private static final Matrix4f toWorld = new Matrix4f();
    private static final Vector3f v = new Vector3f();

    private static RtBuffer vertices, blasBuffer, scratch;
    private static long blas, blasAddress;
    private static boolean built;

    private RtEntities() {}

    /** Called at frame start: drops the previous frame's geometry. */
    public static void beginFrame() {
        vertexCount = 0;
        built = false;
    }

    /**
     * Adds a quad batch drawn with {@code modelView} (positions are the first three floats
     * of each {@code stride}-byte vertex, from position 0 of {@code data}).
     */
    public static void add(ByteBuffer data, int count, int stride, FloatBuffer modelView) {
        if (!RtScene.enabled() || !CameraHistory.valid() || count <= 0 || (count & 3) != 0) return;
        if (vertexCount + count > MAX_VERTICES) return;
        ensureCpu((vertexCount + count) * 12);
        toWorld.set(modelView.position(0));
        CameraHistory.viewInverse().mul(toWorld, toWorld);
        int base = data.position();
        for (int i = 0; i < count; i++) {
            int o = base + i * stride;
            toWorld.transformPosition(data.getFloat(o), data.getFloat(o + 4), data.getFloat(o + 8), v);
            int w = (vertexCount + i) * 12;
            cpu.putFloat(w, v.x).putFloat(w + 4, v.y).putFloat(w + 8, v.z);
        }
        vertexCount += count;
    }

    /** Camera-relative world position of the model-view's origin (for range tests). */
    public static Vector3f origin(FloatBuffer modelView, Vector3f out) {
        toWorld.set(modelView.position(0));
        CameraHistory.viewInverse().mul(toWorld, toWorld);
        return toWorld.getTranslation(out);
    }

    private static void ensureCpu(int bytes) {
        if (cpu.capacity() >= bytes) return;
        ByteBuffer bigger = MemoryUtil.memAlloc(Math.max(bytes, cpu.capacity() * 2));
        MemoryUtil.memCopy(MemoryUtil.memAddress(cpu), MemoryUtil.memAddress(bigger), (long) vertexCount * 12);
        MemoryUtil.memFree(cpu);
        cpu = bigger;
    }

    static boolean ready() { return built; }
    static long blasAddress() { return blasAddress; }

    /**
     * Uploads this frame's vertices and builds the BLAS. Outside rendering, before the
     * TLAS build (which must be separated from this by an AS-build barrier).
     */
    static void build(VkCommandBuffer cmd, MemoryStack stack) {
        built = false;
        if (vertexCount == 0) return;
        DeviceFeatures f = Vulkan.getDeviceInfo().features;

        // The previous frame's build and traces read the single vertex buffer and BLAS.
        VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack);
        pre.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
                        | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_2_SHADER_READ_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre));

        int bytes = vertexCount * 12;
        if (vertices == null || vertices.size < bytes) {
            if (vertices != null) {
                RtBuffer old = vertices;
                RtBuffer.defer(old::destroyNow);
            }
            vertices = RtBuffer.create(Math.max(bytes * 3L / 2, 256 * 1024),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false);
        }
        RtScene.updateBuffer(cmd, vertices.buffer, cpu, bytes);

        VkMemoryBarrier2.Buffer up = VkMemoryBarrier2.calloc(1, stack);
        up.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(up));

        IndexBuffer ib = Drawer.getInstance().getQuadsIndexBuffer().getIndexBuffer(vertexCount);
        boolean shortIndices = ib.indexType == IndexBuffer.IndexType.SHORT;
        int primitives = vertexCount / 4 * 2;

        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0).sType$Default()
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.get(0).geometry().triangles().sType$Default()
                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(12)
                .maxVertex(vertexCount - 1)
                .indexType(shortIndices ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
        geometry.get(0).geometry().triangles().vertexData().deviceAddress(vertices.address);
        geometry.get(0).geometry().triangles().indexData().deviceAddress(RtBuffer.addressOf(ib.getId()));

        VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        info.get(0).sType$Default()
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1)
                .pGeometries(geometry);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(Vulkan.getDevice(), VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                info.get(0), stack.ints(primitives), sizes);

        if (blasBuffer == null || blasBuffer.size < sizes.accelerationStructureSize()) {
            releaseBlas();
            long size = sizes.accelerationStructureSize() * 3 / 2;
            blasBuffer = RtBuffer.create(size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
            LongBuffer pAs = stack.mallocLong(1);
            int rc = vkCreateAccelerationStructureKHR(Vulkan.getDevice(),
                    VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                            .buffer(blasBuffer.buffer).size(size)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, pAs);
            if (rc != VK_SUCCESS) throw new RuntimeException("vkCreateAccelerationStructureKHR (entities): " + rc);
            blas = pAs.get(0);
            blasAddress = vkGetAccelerationStructureDeviceAddressKHR(Vulkan.getDevice(),
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(blas));
        }
        long align = Math.max(1, f.minAccelerationStructureScratchOffsetAlignment);
        if (scratch == null || scratch.size < sizes.buildScratchSize() + align) {
            if (scratch != null) {
                RtBuffer old = scratch;
                RtBuffer.defer(old::destroyNow);
            }
            scratch = RtBuffer.create((sizes.buildScratchSize() + align) * 3 / 2,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
        }
        info.get(0).dstAccelerationStructure(blas);
        info.get(0).scratchData().deviceAddress((scratch.address + align - 1) / align * align);

        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(primitives);
        vkCmdBuildAccelerationStructuresKHR(cmd, info, stack.pointers(range.address()));
        built = true;
    }

    private static void releaseBlas() {
        if (blas == 0L) return;
        long handle = blas;
        RtBuffer buffer = blasBuffer;
        RtBuffer.defer(() -> {
            vkDestroyAccelerationStructureKHR(Vulkan.getDevice(), handle, null);
            buffer.destroyNow();
        });
        blas = 0L;
        blasBuffer = null;
        blasAddress = 0L;
    }

    /** With the device idle (RtScene.destroy flushes the deferred releases after this). */
    static void destroy() {
        releaseBlas();
        if (vertices != null) { RtBuffer old = vertices; RtBuffer.defer(old::destroyNow); vertices = null; }
        if (scratch != null) { RtBuffer old = scratch; RtBuffer.defer(old::destroyNow); scratch = null; }
        built = false;
        vertexCount = 0;
    }
}
