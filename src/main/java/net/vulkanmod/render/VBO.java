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
    private static ByteBuffer scratch = org.lwjgl.system.MemoryUtil.memAlloc(1 << 16);

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
        return scratch;
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
            autoIndexBuffer.checkCapacity(vertexCount);

            // GL_QUADS -> indexed triangles (4 vertices -> 6 indices)
            TerrainRenderer.countDraw(TerrainRenderer.currentLayer, vertexCount);
            drawer.draw(vertexBuffer, autoIndexBuffer.getIndexBuffer(), vertexCount * 3 / 2, 7);
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
            } else {
                TerrainRenderer.applyCurrentSectionMatrix();
            }

            TerrainRenderer.countDraw(TerrainRenderer.currentLayer, vertexCount);
            ChunkBatcher.record(slot, arenaOffset / ChunkBatcher.STRIDE, vertexCount);
            if (TerrainRenderer.DUMP_MVP && TerrainRenderer.dumpFramesLeft > 0) {
                TerrainRenderer.dumpSectionMatrix(slot);
            }
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("chunk batch record", t);
        }
    }

    public void close() {
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
