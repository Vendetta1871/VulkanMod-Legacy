package net.vulkanmod.vulkan.memory;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class AutoIndexBuffer {
    // Highest vertex count addressable by UINT16 indices; above this the buffer switches to UINT32.
    private static final int MAX_SHORT_INDEXABLE = 65536;

    int vertexCount;
    DrawType drawType;
    IndexBuffer.IndexType indexType;
    IndexBuffer indexBuffer;

    public AutoIndexBuffer(int vertexCount, DrawType type) {
        this.drawType = type;

        createIndexBuffer(vertexCount);
    }

    private void createIndexBuffer(int vertexCount) {
        this.vertexCount = vertexCount;
        this.indexType = vertexCount > MAX_SHORT_INDEXABLE
                ? IndexBuffer.IndexType.INT
                : IndexBuffer.IndexType.SHORT;

        int indexCount;
        ByteBuffer buffer;

        switch (drawType) {
            case QUADS -> {
                indexCount = vertexCount * 3 / 2;
                buffer = genQuadIdxs(vertexCount, indexType);
            }
            case TRIANGLE_FAN -> {
                indexCount = (vertexCount - 2) * 3;
                buffer = genTriangleFanIdxs(vertexCount, indexType);
            }
            case TRIANGLE_STRIP -> {
                indexCount = (vertexCount - 2) * 3;
                buffer = genTriangleStripIdxs(vertexCount, indexType);
            }
            default -> throw new RuntimeException("unknown drawType");
        }

        indexBuffer = new IndexBuffer(indexCount * indexType.size, MemoryTypes.GPU_MEM);
        indexBuffer.indexType = indexType;
        indexBuffer.copyBuffer(buffer);
        // The host-side index data is only needed to fill the GPU buffer.
        MemoryUtil.memFree(buffer);
    }

    public void checkCapacity(int vertexCount) {
        if(vertexCount > this.vertexCount) {
            // Grow to at least the requested size; doubling alone can lag far behind
            // a large request and would loop through repeated reallocations.
            int newVertexCount = Math.max(vertexCount, this.vertexCount * 2);
            System.out.println("Reallocating AutoIndexBuffer from " + this.vertexCount + " to " + newVertexCount);

            // In-flight command buffers may still bind the old index buffer; addToFreeable
            // defers the destroy until they have finished.
            MemoryManager.addToFreeable(indexBuffer);
            createIndexBuffer(newVertexCount);
        }
    }

    /** Fills a freshly allocated host buffer with {@code indexCount} indices from {@code source}. */
    private static ByteBuffer genIndices(int indexCount, IndexBuffer.IndexType type, IndexSource source) {
        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * type.size);

        if(type == IndexBuffer.IndexType.INT) {
            IntBuffer idxs = buffer.asIntBuffer();
            for(int i = 0; i < indexCount; i++) {
                idxs.put(i, source.get(i));
            }
        }
        else {
            ShortBuffer idxs = buffer.asShortBuffer();
            for(int i = 0; i < indexCount; i++) {
                idxs.put(i, (short) source.get(i));
            }
        }

        buffer.rewind();
        return buffer;
    }

    private static ByteBuffer genQuadIdxs(int vertexCount, IndexBuffer.IndexType type) {
        // Per quad (4 vertices): triangles (0,1,2) and (0,2,3).
        int[] pattern = { 0, 1, 2, 0, 2, 3 };

        return genIndices(vertexCount * 3 / 2, type,
                (index) -> (index / 6) * 4 + pattern[index % 6]);
    }

    private static ByteBuffer genTriangleFanIdxs(int vertexCount, IndexBuffer.IndexType type) {
        // Per triangle i: (0, i + 1, i + 2).
        return genIndices((vertexCount - 2) * 3, type, (index) -> {
            int triangle = index / 3, corner = index % 3;
            return corner == 0 ? 0 : triangle + corner;
        });
    }

    private static ByteBuffer genTriangleStripIdxs(int vertexCount, IndexBuffer.IndexType type) {
        // Per triangle i: (i, i + 1, i + 2).
        return genIndices((vertexCount - 2) * 3, type,
                (index) -> index / 3 + index % 3);
    }

    public IndexBuffer getIndexBuffer() { return indexBuffer; }

    public IndexBuffer.IndexType getIndexType() { return indexType; }

    private interface IndexSource {
        int get(int index);
    }

    public enum DrawType {
        QUADS(7),
        TRIANGLE_FAN(6),
        TRIANGLE_STRIP(5);

        public final int n;

        DrawType (int n) {
            this.n = n;
        }
    }
}
