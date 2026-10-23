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
 * <p>Chunk geometry is uploaded once per rebuild as a device-local vertex buffer and
 * drawn indexed; the quad-to-triangle indices come from the shared auto index buffer,
 * mirroring the original VulkanMod VBO.</p>
 */
public class VBO {
    private VertexBuffer vertexBuffer;
    private int vertexCount;

    /**
     * Uploads the vertex data of one chunk layer. The buffer must have position 0 and
     * its limit set to the written byte count (what {@code BufferBuilder.finishDrawing}
     * leaves behind).
     */
    public void upload(ByteBuffer data, VertexFormat format, int vertexCount) {
        if (vertexBuffer != null) {
            vertexBuffer.freeBuffer();
            vertexBuffer = null;
        }

        this.vertexCount = vertexCount;
        if (vertexCount <= 0) {
            return;
        }

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

    /** Draws the buffer as indexed quads with the currently bound pipeline. */
    public void draw() {
        if (vertexBuffer == null || vertexCount <= 0) {
            return;
        }
        if (Drawer.skipRendering || !Drawer.isActive()) {
            return;
        }
        if (VRenderSystem.getShaderPipeline() == null) {
            return;
        }
        if (TerrainRenderer.NO_TRANSLUCENT && TerrainRenderer.currentLayer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT) {
            return;
        }

        try {
            // Both matrices are read right before the draw: at the head of the chunk
            // layer pass the game has not set up the projection yet (it happens later
            // inside renderWorldPass), and the modelview carries the chunk offset.
            VRenderSystem.updateModelViewMatrix();
            VRenderSystem.updateProjectionMatrix();

            Drawer drawer = Drawer.getInstance();
            AutoIndexBuffer autoIndexBuffer = drawer.getQuadsIndexBuffer();
            autoIndexBuffer.checkCapacity(vertexCount);

            // GL_QUADS -> indexed triangles (4 vertices -> 6 indices)
            drawer.draw(vertexBuffer, autoIndexBuffer.getIndexBuffer(), vertexCount * 3 / 2, 7);
        } catch (Throwable t) {
            net.vulkanmod.render.TerrainRenderer.reportOnce("chunk draw", t);
        }
    }

    public void close() {
        if (vertexBuffer != null) {
            vertexBuffer.freeBuffer();
            vertexBuffer = null;
        }
        vertexCount = 0;
    }
}
