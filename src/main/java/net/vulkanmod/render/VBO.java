package net.vulkanmod.render;

import net.minecraft.client.renderer.vertex.VertexFormat;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.AutoIndexBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.VertexBuffer;

import java.nio.ByteBuffer;

/**
 * Vulkan-side counterpart of one {@code net.minecraft.client.renderer.vertex.VertexBuffer}.
 *
 * <p>Two submission models are supported, selected by {@link TerrainRenderer#BATCH}:</p>
 * <ul>
 *   <li><b>default</b> - the section's geometry is a standalone device-local vertex buffer,
 *   drawn with its own draw call and per-chunk modelview;</li>
 *   <li><b>batched</b> - the geometry lives in a slice of the shared {@link ChunkArena}, and
 *   drawing only records the section into {@link ChunkBatcher}, which later submits the whole
 *   layer as one indirect draw.</li>
 * </ul>
 */
public class VBO {
    private VertexBuffer vertexBuffer;
    private int vertexCount;

    // Batched path: this section's byte range within the shared arena (-1 when unset), its
    // persistent matrix slot (-1 when unset), and a reused scratch buffer for slot interleaving.
    private int arenaOffset = -1;
    private int arenaBytes;
    // The arena generation this range was allocated in; frees from an older world (after
    // ChunkArena.reset) are ignored so they cannot punch holes into new allocations.
    private long arenaGeneration;
    private int slot = -1;
    /** Ray-tracing state of this section (T3), only when the RT scene is on. */
    private net.vulkanmod.vulkan.rt.RtScene.Section rt;
    private static ByteBuffer scratch = org.lwjgl.system.MemoryUtil.memAlloc(1 << 16);

    /** This layer's geometry is in the arena of the current world. */
    public boolean inArena() {
        return arenaOffset >= 0 && vertexCount > 0 && arenaGeneration == ChunkBatcher.arena().generation();
    }

    public int slot() {
        return slot;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** First vertex in the arena. */
    public int arenaVertex() {
        return arenaOffset / ChunkBatcher.STRIDE;
    }

    private static int rtMask(net.minecraft.util.BlockRenderLayer layer) {
        return layer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT ? net.vulkanmod.vulkan.rt.RtScene.MASK_TRANSLUCENT
                // Both cutout layers are alpha tested (CUTOUT_MIPPED: leaves, grass sides).
                : layer == net.minecraft.util.BlockRenderLayer.CUTOUT || layer == net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED
                ? net.vulkanmod.vulkan.rt.RtScene.MASK_CUTOUT : net.vulkanmod.vulkan.rt.RtScene.MASK_OPAQUE;
    }

    /**
     * Adds geometry uploaded while ray tracing was off to the RT scene (it is still in the
     * chunk arena), so turning RT on needs no chunk reload.
     */
    public void registerRt(net.minecraft.util.math.BlockPos origin, net.minecraft.util.BlockRenderLayer layer) {
        if (rt != null && rt.isRegistered()) return;
        if (arenaOffset < 0 || vertexCount <= 0 || arenaGeneration != ChunkBatcher.arena().generation()) return;
        if (rt == null) rt = new net.vulkanmod.vulkan.rt.RtScene.Section();
        net.vulkanmod.vulkan.rt.RtScene.onUpload(rt, arenaOffset, vertexCount);
        net.vulkanmod.vulkan.rt.RtScene.onDraw(rt, origin.getX(), origin.getY(), origin.getZ(), rtMask(layer));
    }

    /** Places this layer's uploaded geometry in the RT scene at the render chunk's origin. */
    public void placeRt(net.minecraft.util.math.BlockPos origin, net.minecraft.util.BlockRenderLayer layer) {
        if (rt != null && rt.isRegistered()) {
            net.vulkanmod.vulkan.rt.RtScene.onDraw(rt, origin.getX(), origin.getY(), origin.getZ(), rtMask(layer));
        }
    }

    /** The render chunk moved: its old geometry leaves the RT scene until the rebuild. */
    public void unplace() {
        if (rt != null) net.vulkanmod.vulkan.rt.RtScene.onClose(rt);
    }

    /**
     * Uploads the vertex data of one chunk layer. The buffer must have position 0 and
     * its limit set to the written byte count (what {@code BufferBuilder.finishDrawing}
     * leaves behind).
     */
    public void upload(ByteBuffer data, VertexFormat format, int vertexCount) {
        // Only the BLOCK layout (28-byte stride) matches the batched arena vertex format;
        // other layers (sky, stars use 12-byte POSITION) would read past the source in
        // interleaveSlot, so they keep the standalone-buffer path even in BATCH mode.
        if (TerrainRenderer.BATCH && format == net.minecraft.client.renderer.vertex.DefaultVertexFormats.BLOCK) {
            uploadBatched(data, format, vertexCount);
            return;
        }

        // A section can switch paths between uploads (e.g. BATCH toggled); release
        // whatever the previous upload left behind.
        if (arenaOffset >= 0) {
            ChunkBatcher.arena().free(arenaOffset, arenaBytes, arenaGeneration);
            arenaOffset = -1;
        }
        if (slot >= 0) {
            ChunkBatcher.freeSlot(slot);
            slot = -1;
        }

        if (vertexBuffer != null) {
            TerrainRenderer.countUpload(TerrainRenderer.currentLayer, this.vertexCount, vertexCount);
            vertexBuffer.freeBuffer();
            vertexBuffer = null;
        }
        if (TerrainRenderer.LOG_UPLOADS) {
            Initializer.LOGGER.info("[terrain] UPLOAD {} -> {} verts", this.vertexCount, vertexCount);
        }

        this.vertexCount = vertexCount;
        if (vertexCount <= 0) {
            return;
        }

        TerrainRenderer.checkVertexRange(data, vertexCount, format.getSize());

        try {
            // Staging safety for uploads outside the frame window is enforced inside
            // StagingBuffer.copyBuffer (see Drawer.ensureStagingFree).
            vertexBuffer = new VertexBuffer(format.getSize() * vertexCount, MemoryTypes.GPU_MEM);
            vertexBuffer.copyToVertexBuffer(format.getSize(), vertexCount, data);
        } catch (Throwable t) {
            vertexBuffer = null;
            this.vertexCount = 0;
            net.vulkanmod.render.TerrainRenderer.reportOnce("chunk upload", t);
        }
    }

    private void uploadBatched(ByteBuffer data, VertexFormat format, int vertexCount) {
        ChunkArena arena = ChunkBatcher.arena();

        // Release the previous slice + slot on rebuild.
        if (arenaOffset >= 0) {
            TerrainRenderer.countUpload(TerrainRenderer.currentLayer, this.vertexCount, vertexCount);
            arena.free(arenaOffset, arenaBytes, arenaGeneration);
            arenaOffset = -1;
        }
        if (slot >= 0) {
            ChunkBatcher.freeSlot(slot);
            slot = -1;
        }

        this.vertexCount = vertexCount;
        if (rt != null) {
            // Old geometry is gone; a new BLAS follows the upload below.
            net.vulkanmod.vulkan.rt.RtScene.onClose(rt);
        }
        if (vertexCount <= 0) {
            return;
        }

        TerrainRenderer.checkVertexRange(data, vertexCount, format.getSize());

        try {
            slot = ChunkBatcher.allocSlot();
            int bytes = ChunkBatcher.STRIDE * vertexCount;
            ByteBuffer interleaved = interleaveSlot(data, vertexCount, slot);
            arenaOffset = arena.alloc(bytes);
            arenaBytes = bytes;
            arenaGeneration = arena.generation();
            arena.upload(arenaOffset, interleaved, bytes);
            if (net.vulkanmod.vulkan.rt.RtScene.enabled()) {
                if (rt == null) rt = new net.vulkanmod.vulkan.rt.RtScene.Section();
                // Placed when vanilla accepts the compiled chunk (RenderChunkM.setCompiledChunk):
                // an upload of a cancelled task must not enter the scene at the moved chunk's spot.
                net.vulkanmod.vulkan.rt.RtScene.onUpload(rt, arenaOffset, vertexCount);
            }
        } catch (Throwable t) {
            if (arenaOffset >= 0) {
                arena.free(arenaOffset, arenaBytes, arenaGeneration);
                arenaOffset = -1;
            }
            if (slot >= 0) {
                ChunkBatcher.freeSlot(slot);
                slot = -1;
            }
            this.vertexCount = 0;
            TerrainRenderer.reportOnce("chunk batch upload", t);
        }
    }

    /**
     * Copies the BLOCK-stride source vertices into the reused scratch buffer, appending this
     * section's slot as the trailing float so every vertex carries its matrix index.
     */
    private static ByteBuffer interleaveSlot(ByteBuffer data, int vertexCount, int slot) {
        int bytes = ChunkBatcher.STRIDE * vertexCount;
        if (scratch.capacity() < bytes) {
            org.lwjgl.system.MemoryUtil.memFree(scratch);
            scratch = org.lwjgl.system.MemoryUtil.memAlloc(bytes);
        }
        scratch.clear();

        ByteBuffer src = data.duplicate();
        src.position(0);
        for (int v = 0; v < vertexCount; ++v) {
            src.limit(src.position() + ChunkBatcher.BLOCK_STRIDE);
            scratch.put(src);          // copies exactly BLOCK_STRIDE bytes
            scratch.putFloat(slot);
        }
        scratch.flip();
        return ChunkBatcher.vertexFlags() != 0 ? extend(data, vertexCount) : scratch;
    }

    private static ByteBuffer extended = org.lwjgl.system.MemoryUtil.memAlloc(1 << 20);

    /**
     * Adds a pack's extended attributes to the interleaved vertices (runtime.md, "Vertex
     * data"). Vanilla quads are planar, so normal and tangent are per quad: from the diagonals
     * and from the positions and texcoords.
     */
    private static ByteBuffer extend(ByteBuffer data, int vertexCount) {
        int flags = ChunkBatcher.vertexFlags();
        int stride = ChunkBatcher.STRIDE, base = ChunkBatcher.BLOCK_STRIDE + 4;
        if (extended.capacity() < stride * vertexCount) {
            org.lwjgl.system.MemoryUtil.memFree(extended);
            extended = org.lwjgl.system.MemoryUtil.memAlloc(stride * vertexCount);
        }
        extended.clear();
        float[] p = new float[12], t = new float[8];
        byte[] n = new byte[4], tangent = new byte[4];
        for (int q = 0; q + 3 < vertexCount; q += 4) {
            for (int v = 0; v < 4; v++) {
                int at = (q + v) * ChunkBatcher.BLOCK_STRIDE;
                for (int c = 0; c < 3; c++) p[v * 3 + c] = data.getFloat(at + c * 4);
                t[v * 2] = data.getFloat(at + 16);
                t[v * 2 + 1] = data.getFloat(at + 20);
            }
            float d0x = p[6] - p[0], d0y = p[7] - p[1], d0z = p[8] - p[2];
            float d1x = p[9] - p[3], d1y = p[10] - p[4], d1z = p[11] - p[5];
            float nx = d0y * d1z - d0z * d1y, ny = d0z * d1x - d0x * d1z, nz = d0x * d1y - d0y * d1x;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0.0f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            n[0] = snorm8(nx);
            n[1] = snorm8(ny);
            n[2] = snorm8(nz);

            // Tangent: the position direction along which u grows.
            float e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2];
            float e2x = p[9] - p[0], e2y = p[10] - p[1], e2z = p[11] - p[2];
            float u1 = t[2] - t[0], v1 = t[3] - t[1], u2 = t[6] - t[0], v2 = t[7] - t[1];
            float sign = u1 * v2 - u2 * v1 < 0.0f ? -1.0f : 1.0f;
            float tx = (e1x * v2 - e2x * v1) * sign, ty = (e1y * v2 - e2y * v1) * sign, tz = (e1z * v2 - e2z * v1) * sign;
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl > 0.0f) {
                tx /= tl;
                ty /= tl;
                tz /= tl;
            }
            tangent[0] = snorm8(tx);
            tangent[1] = snorm8(ty);
            tangent[2] = snorm8(tz);
            tangent[3] = snorm8(sign);

            short midU = (short) ((t[0] + t[2] + t[4] + t[6]) * 0.25f * 65535.0f);
            short midV = (short) ((t[1] + t[3] + t[5] + t[7]) * 0.25f * 65535.0f);
            float cx = (p[0] + p[3] + p[6] + p[9]) * 0.25f, cy = (p[1] + p[4] + p[7] + p[10]) * 0.25f,
                    cz = (p[2] + p[5] + p[8] + p[11]) * 0.25f;

            for (int v = 0; v < 4; v++) {
                int at = (q + v) * base;
                scratch.limit(at + base).position(at);
                int start = extended.position();
                extended.put(scratch);
                if ((flags & net.vulkanmod.pack.plan.FramePlan.VERTEX_NORMAL) != 0) extended.put(n[0]).put(n[1]).put(n[2]).put((byte) 0);
                if ((flags & net.vulkanmod.pack.plan.FramePlan.VERTEX_TANGENT) != 0) extended.put(tangent);
                if ((flags & net.vulkanmod.pack.plan.FramePlan.VERTEX_MID_TEX) != 0) extended.putShort(midU).putShort(midV);
                if ((flags & BLOCK_TAG) != 0) {
                    // BlockRendererDispatcherM packed them over the unused high bytes of UV2.
                    short lu = extended.getShort(start + 24), lv = extended.getShort(start + 26);
                    extended.putShort(start + 24, (short) (lu & 0xFF)).putShort(start + 26, (short) (lv & 0xFF));
                    int tag = (lu >> 8 & 0xFF) | (lv >> 8 & 0xFF) << 8;
                    if ((flags & net.vulkanmod.pack.plan.FramePlan.VERTEX_BLOCK) != 0) {
                        int attribute = net.vulkanmod.shader.PackIds.block(tag);
                        extended.putShort((short) attribute).putShort((short) (attribute >>> 16));
                    }
                    if ((flags & net.vulkanmod.pack.plan.FramePlan.VERTEX_MID_BLOCK) != 0) {
                        // The center of the quad, just behind its face, is inside the block.
                        extended.put(midOffset(cx - nx * 0.001f, p[v * 3])).put(midOffset(cy - ny * 0.001f, p[v * 3 + 1]))
                                .put(midOffset(cz - nz * 0.001f, p[v * 3 + 2])).put((byte) blockLight(tag));
                    }
                }
            }
        }
        extended.flip();
        return extended;
    }

    private static final int BLOCK_TAG = net.vulkanmod.pack.plan.FramePlan.VERTEX_BLOCK | net.vulkanmod.pack.plan.FramePlan.VERTEX_MID_BLOCK;

    /** From {@code vertex} to the middle of the block at {@code inside}, in 1/64 of a block. */
    private static byte midOffset(float inside, float vertex) {
        float offset = ((float) Math.floor(inside) + 0.5f - vertex) * 64.0f;
        return (byte) Math.round(Math.max(-127.0f, Math.min(127.0f, offset)));
    }

    /** {@code tag} is the block's id and, from bit 12, its metadata. */
    @SuppressWarnings("deprecation")
    private static int blockLight(int tag) {
        return net.minecraft.block.Block.getBlockById(tag & 0xFFF).getStateFromMeta(tag >>> 12 & 0xF).getLightValue();
    }

    private static byte snorm8(float v) {
        return (byte) Math.round(v * 127.0f);
    }

    /** Draws the buffer as indexed quads with the currently bound pipeline. */
    public void draw() {
        if (vertexCount <= 0) {
            return;
        }
        if (Drawer.skipRendering || !Drawer.isActive()) {
            return;
        }
        if (TerrainRenderer.NO_TRANSLUCENT && TerrainRenderer.currentLayer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT) {
            return;
        }

        // The path is chosen per buffer, not per global mode: in BATCH mode only BLOCK-format
        // sections live in the arena (vertexBuffer == null); other layers keep their own buffer.
        if (vertexBuffer == null) {
            net.vulkanmod.debug.FrameProfiler.chunkRecordBegin();
            drawBatched();
            net.vulkanmod.debug.FrameProfiler.chunkRecordEnd();
            return;
        }

        if (VRenderSystem.getShaderPipeline() == null) {
            return;
        }
        if (vertexBuffer == null) {
            return;
        }

        try {
            // Both matrices are read right before the draw: at the head of the chunk
            // layer pass the game has not set up the projection yet (it happens later
            // inside renderWorldPass), and the modelview carries the chunk offset.
            VRenderSystem.updateModelViewMatrix();
            VRenderSystem.updateProjectionMatrix();
            TerrainRenderer.checkModelviewUnique(TerrainRenderer.currentLayer, vertexCount);

            Drawer drawer = Drawer.getInstance();
            AutoIndexBuffer autoIndexBuffer = drawer.getQuadsIndexBuffer();

            // GL_QUADS -> indexed triangles (4 vertices -> 6 indices)
            TerrainRenderer.countDraw(TerrainRenderer.currentLayer, vertexCount);
            drawer.draw(vertexBuffer, autoIndexBuffer.getIndexBuffer(vertexCount), vertexCount * 3 / 2, 7);
        } catch (Throwable t) {
            net.vulkanmod.render.TerrainRenderer.reportOnce("chunk draw", t);
        }
    }

    private void drawBatched() {
        if (arenaOffset < 0 || slot < 0) {
            return;
        }
        try {
            // Derive this section's MVP on the CPU from the layer's camera matrices and the
            // stashed section origin (no per-section GL matrix query).
            if (TerrainRenderer.NOQUERY) {
                VRenderSystem.updateProjectionMatrix();
                VRenderSystem.updateModelViewMatrix();
            } else if (TerrainRenderer.DUMP_MVP) {
                TerrainRenderer.applyCurrentSectionMatrix();
            }

            TerrainRenderer.countDraw(TerrainRenderer.currentLayer, vertexCount);
            ChunkBatcher.record(slot, arenaOffset / ChunkBatcher.STRIDE, vertexCount);
            if (rt != null && rt.isRegistered()) {
                net.vulkanmod.vulkan.rt.RtScene.onDraw(rt, TerrainRenderer.pendingX(), TerrainRenderer.pendingY(),
                        TerrainRenderer.pendingZ(), rtMask(TerrainRenderer.currentLayer));
            }
            if (TerrainRenderer.DUMP_MVP && TerrainRenderer.dumpFramesLeft > 0) {
                TerrainRenderer.dumpSectionMatrix(slot);
            }
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("chunk batch record", t);
        }
    }

    public void close() {
        if (rt != null) {
            net.vulkanmod.vulkan.rt.RtScene.onClose(rt);
        }
        // Release both possible storages unconditionally: a section may have been
        // uploaded through either path over its lifetime.
        if (arenaOffset >= 0) {
            ChunkBatcher.arena().free(arenaOffset, arenaBytes, arenaGeneration);
            arenaOffset = -1;
        }
        if (slot >= 0) {
            ChunkBatcher.freeSlot(slot);
            slot = -1;
        }

        if (vertexBuffer != null) {
            vertexBuffer.freeBuffer();
            vertexBuffer = null;
        }
        vertexCount = 0;
    }
}
