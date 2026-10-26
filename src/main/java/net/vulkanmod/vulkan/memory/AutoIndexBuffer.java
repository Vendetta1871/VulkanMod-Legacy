package net.vulkanmod.vulkan.memory;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class AutoIndexBuffer {
    // Highest vertex count addressable by UINT16 indices.
    private static final int MAX_SHORT_INDEXABLE = 65536;

    private static final int RANGE_SHORT = 0, RANGE_INT = 1;

    /**
     * One buffer per index width (N25): almost every draw stays under 65536 vertices and
     * keeps fetching cheap UINT16 indices even after an occasional huge draw has forced a
     * UINT32 buffer into existence. The buffer starts in the UINT16 range; the old one
     * opened at 100000 vertices, so every quad draw fetched 4-byte indices. The UINT32
     * range is created on first use.
     */
    private final IndexBuffer[] indexBuffers = new IndexBuffer[2];
    private final int[] capacities = new int[2];
    private final DrawType drawType;

    public AutoIndexBuffer(int vertexCount, DrawType type) {
        this.drawType = type;

        createIndexBuffer(RANGE_SHORT, Math.min(vertexCount, MAX_SHORT_INDEXABLE));
    }

    /**
     * The shared index buffer for a draw of {@code vertexCount} vertices, grown when the
     * draw does not fit the current buffer of that index width. In-flight command buffers
     * may still bind the replaced buffer, so its destroy is deferred (addToFreeable).
     */
    public IndexBuffer getIndexBuffer(int vertexCount) {
        int range = vertexCount > MAX_SHORT_INDEXABLE ? RANGE_INT : RANGE_SHORT;
        if (vertexCount > capacities[range]) {
            // Grow to at least the requested size; doubling alone can lag far behind
            // a large request and would loop through repeated reallocations.
            int newCapacity = Math.max(vertexCount, Math.max(capacities[range] * 2, 1024));
            if (range == RANGE_SHORT) {
                newCapacity = Math.min(newCapacity, MAX_SHORT_INDEXABLE);
            }
            System.out.println("Reallocating AutoIndexBuffer (" + (range == RANGE_SHORT ? "UINT16" : "UINT32")
                    + ") from " + capacities[range] + " to " + newCapacity);

            if (indexBuffers[range] != null) {
                MemoryManager.addToFreeable(indexBuffers[range]);
            }
            createIndexBuffer(range, newCapacity);
        }
        return indexBuffers[range];
    }

    private void createIndexBuffer(int range, int vertexCount) {
        capacities[range] = vertexCount;
        IndexBuffer.IndexType indexType = range == RANGE_SHORT
                ? IndexBuffer.IndexType.SHORT
                : IndexBuffer.IndexType.INT;

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

        IndexBuffer indexBuffer = new IndexBuffer(indexCount * indexType.size, MemoryTypes.GPU_MEM);
        indexBuffer.indexType = indexType;
        indexBuffer.copyBuffer(buffer);
        // The host-side index data is only needed to fill the GPU buffer.
        MemoryUtil.memFree(buffer);

        indexBuffers[range] = indexBuffer;
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
