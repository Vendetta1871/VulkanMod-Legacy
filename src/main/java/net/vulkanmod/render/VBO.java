package net.vulkanmod.render;

import net.minecraft.client.renderer.vertex.VertexFormat;
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
    private int slot = -1;
    private static ByteBuffer scratch = org.lwjgl.system.MemoryUtil.memAlloc(1 << 16);

    /**
     * Uploads the vertex data of one chunk layer. The buffer must have position 0 and
     * its limit set to the written byte count (what {@code BufferBuilder.finishDrawing}
     * leaves behind).
     */
    public void upload(ByteBuffer data, VertexFormat format, int vertexCount) {
        if (TerrainRenderer.BATCH) {
            uploadBatched(data, format, vertexCount);
            return;
        }

        if (vertexBuffer != null) {
            TerrainRenderer.countUpload(TerrainRenderer.currentLayer, this.vertexCount, vertexCount);
            vertexBuffer.freeBuffer();
            vertexBuffer = null;
        }

        this.vertexCount = vertexCount;
        if (vertexCount <= 0) {
            return;
        }

        TerrainRenderer.checkVertexRange(data, vertexCount, format.getSize());

        try {
            // Uploads outside the Vulkan frame window (rare; e.g. immediate chunk updates
            // issued between frames) must not overwrite staging regions still in flight.
            if (!Drawer.isActive()) {
                net.vulkanmod.vulkan.Synchronization.waitFences();
            }

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
            arena.free(arenaOffset, arenaBytes);
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
            arena.upload(arenaOffset, interleaved, bytes);
        } catch (Throwable t) {
            if (arenaOffset >= 0) {
                arena.free(arenaOffset, arenaBytes);
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

        if (TerrainRenderer.BATCH) {
            drawBatched();
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
            // Read the projection first, then the per-chunk modelview; updateModelViewMatrix
            // recomputes MVP from the two, and MVP is what the batched shader reads per slot.
            VRenderSystem.updateProjectionMatrix();
            VRenderSystem.updateModelViewMatrix();

            TerrainRenderer.countDraw(TerrainRenderer.currentLayer, vertexCount);
            ChunkBatcher.record(slot, arenaOffset / ChunkBatcher.STRIDE, vertexCount);
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("chunk batch record", t);
        }
    }

    public void close() {
        if (TerrainRenderer.BATCH) {
            if (arenaOffset >= 0) {
                ChunkBatcher.arena().free(arenaOffset, arenaBytes);
                arenaOffset = -1;
            }
            if (slot >= 0) {
                ChunkBatcher.freeSlot(slot);
                slot = -1;
            }
            vertexCount = 0;
            return;
        }

        if (vertexBuffer != null) {
            vertexBuffer.freeBuffer();
            vertexBuffer = null;
        }
        vertexCount = 0;
    }
}
