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
 * instance carries {@link RtScene#MASK_ENTITY}: shadow, indirect and reflection rays see it.
 * Each model face (quad) gets one color - its texture at the face's UV center times the
 * entity tint - and the entity's light, for the hit shader. GPU buffer: per quad
 * (color RGBA8, sky | block light << 8) then the vertex positions.
 */
public final class RtEntities {

    private static final int MAX_VERTICES = 1 << 20;

    /** An entity texture's pixels (RGBA8, rows top down). */
    public record Texture(int width, int height, byte[] rgba) {}

    private static ByteBuffer cpu = MemoryUtil.memAlloc(64 * 1024 * 12);
    private static ByteBuffer quadData = MemoryUtil.memAlloc(16 * 1024 * 8);
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
    public static void add(ByteBuffer data, int count, int stride, FloatBuffer modelView,
                           int uvOffset, Texture texture, float r, float g, float b, int sky, int block) {
        if (!RtScene.enabled() || !CameraHistory.valid() || count <= 0 || (count & 3) != 0) return;
        if (vertexCount + count > MAX_VERTICES) return;
        ensureCpu((vertexCount + count) * 12);
        ensureQuads((vertexCount + count) / 4 * 8);
        toWorld.set(modelView.position(0));
        CameraHistory.viewInverse().mul(toWorld, toWorld);
        int base = data.position();
        for (int i = 0; i < count; i++) {
            int o = base + i * stride;
            toWorld.transformPosition(data.getFloat(o), data.getFloat(o + 4), data.getFloat(o + 8), v);
            int w = (vertexCount + i) * 12;
            cpu.putFloat(w, v.x).putFloat(w + 4, v.y).putFloat(w + 8, v.z);
        }
        int light = Math.min(15, Math.max(0, sky)) | Math.min(15, Math.max(0, block)) << 8;
        for (int q = 0; q < count / 4; q++) {
            int color = 0xFF999999;   // no CPU pixels (atlas items): neutral grey
            if (texture != null && uvOffset >= 0) {
                float u = 0, vv = 0;
                for (int k = 0; k < 4; k++) {
                    int o = base + (q * 4 + k) * stride + uvOffset;
                    u += data.getFloat(o);
                    vv += data.getFloat(o + 4);
                }
                int x = Math.floorMod((int) Math.floor(u / 4 * texture.width()), texture.width());
                int y = Math.floorMod((int) Math.floor(vv / 4 * texture.height()), texture.height());
                int t = (y * texture.width() + x) * 4;
                byte[] px = texture.rgba();
                color = (int) (Math.min(1.0f, (px[t] & 0xFF) / 255.0f * r) * 255)
                        | (int) (Math.min(1.0f, (px[t + 1] & 0xFF) / 255.0f * g) * 255) << 8
                        | (int) (Math.min(1.0f, (px[t + 2] & 0xFF) / 255.0f * b) * 255) << 16
                        | 0xFF << 24;
            }
            int o = (vertexCount / 4 + q) * 8;
            quadData.putInt(o, color).putInt(o + 4, light);
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

    private static void ensureQuads(int bytes) {
        if (quadData.capacity() >= bytes) return;
        ByteBuffer bigger = MemoryUtil.memAlloc(Math.max(bytes, quadData.capacity() * 2));
        MemoryUtil.memCopy(MemoryUtil.memAddress(quadData), MemoryUtil.memAddress(bigger), (long) vertexCount / 4 * 8);
        MemoryUtil.memFree(quadData);
        quadData = bigger;
    }

    static boolean ready() { return built; }
    static long blasAddress() { return blasAddress; }
    /** Device address of the quad data (the vertex positions follow it). */
    static long dataAddress() { return vertices.address; }
    static int quadCount() { return builtQuads; }
    private static int builtQuads;

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

        int quadBytes = vertexCount / 4 * 8;
        int bytes = quadBytes + vertexCount * 12;
        if (vertices == null || vertices.size < bytes) {
            if (vertices != null) {
                RtBuffer old = vertices;
                RtBuffer.defer(old::destroyNow);
            }
            vertices = RtBuffer.create(Math.max(bytes * 3L / 2, 256 * 1024),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    false);
        }
        RtScene.updateBuffer(cmd, vertices.buffer, quadData, quadBytes);
        RtScene.updateBuffer(cmd, vertices.buffer, quadBytes, cpu, vertexCount * 12);

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
        geometry.get(0).geometry().triangles().vertexData().deviceAddress(vertices.address + quadBytes);
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
        builtQuads = vertexCount / 4;
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
