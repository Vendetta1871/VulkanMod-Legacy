package net.vulkanmod.vulkan.shader;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public class UBO extends AlignedStruct {
    private final int binding;
    private final int flags;
    private ByteBuffer buffer;
    private List<Field> fields = new ArrayList<>();
    private int currentOffset = 0;
    private int size = 0;

    public UBO(int binding, int type) {
        this.binding = binding;
        this.flags = type;
        this.buffer = MemoryUtil.memAlloc(1024);
        this.lastData = ByteBuffer.allocateDirect(this.buffer.capacity());
    }

    public void addField(Field field) {
        fields.add(field);
        this.size = field.getOffset() + field.getSize();
    }

    public void update() {
        for(Field field : fields) {
            field.update(buffer);
        }
        Field last = fields.get(fields.size() - 1);
        buffer.limit((last.getOffset() + last.getSize()) * Float.BYTES);
        buffer.position(0);
    }

    // H10: the UBO ring is a per-frame bump allocator, so an offset uploaded earlier in
    // the same window keeps its bytes for the whole window. When update() just produced
    // byte-identical data to that upload, the ring slot and memcpy are skipped and the
    // old offset is reused.
    private long lastWindow = -1;
    private int lastGeneration = -1;
    private int lastOffset;
    // R13: a direct mirror of the last uploaded bytes, compared with ByteBuffer.mismatch
    // instead of a byte-by-byte Java loop. Assigned in the ctor (needs buffer.capacity()).
    private final ByteBuffer lastData;

    /** After update(): true when this draw must go into a fresh ring slot. */
    public boolean needsUpload(long window, int generation) {
        return window != lastWindow || generation != lastGeneration
                || buffer.limit() != lastData.limit() || buffer.mismatch(lastData) != -1;
    }

    /** Remembers the bytes behind the ring offset just handed out. */
    public void recordUpload(long window, int generation, int offset) {
        int len = buffer.limit();
        lastData.limit(len);
        MemoryUtil.memCopy(MemoryUtil.memAddress0(buffer), MemoryUtil.memAddress0(lastData), len);
        lastWindow = window;
        lastGeneration = generation;
        lastOffset = offset;
    }

    /** The offset recorded by the last {@link #recordUpload}; valid in its window. */
    public int getLastOffset() {
        return lastOffset;
    }

    public int getBinding() {
        return binding;
    }

    public int getFlags() {
        return flags;
    }

    public int getCurrentOffset() {
        return currentOffset;
    }

    public void setCurrentOffset(int offset) {
        currentOffset = offset;
    }

    public int getSize() {
        return size * 4;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public FloatBuffer getFloatBuffer() {
        return buffer.asFloatBuffer();
    }

    public void resetOffset() { currentOffset = 0;}

}
