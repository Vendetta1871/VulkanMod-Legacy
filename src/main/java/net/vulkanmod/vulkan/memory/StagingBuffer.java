package net.vulkanmod.vulkan.memory;

import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static net.vulkanmod.vulkan.memory.MemoryManager.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class StagingBuffer extends Buffer{

    public StagingBuffer(int bufferSize) {
        super(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.HOST_MEM);
        this.usedBytes = 0;
        this.offset = 0;

        this.createStagingBuffer(bufferSize);
    }

    //TODO: use createBuffer instead
    private void createStagingBuffer(int bufferSize) {
        this.createBuffer(bufferSize);
    }

    public void copyBuffer(int size, ByteBuffer byteBuffer) {

        // CPU writes must not clobber staging data a submit still in flight is about to
        // read. Inside the frame window the slot's frame fence was already waited on in
        // Drawer.initiateRenderPass(); outside it nothing was (see ensureStagingFree).
        if(!Drawer.isActive()) {
            Drawer.ensureStagingFree();
        }

        // Copy offsets must be aligned to the texel block size of the destination image
        // (VUID-VkBufferImageCopy-bufferOffset-01558); 16 bytes covers every format used here.
        int alignedOffset = (usedBytes + 15) & ~15;

        if(size > this.bufferSize - alignedOffset) {
            resizeBuffer((int)(this.bufferSize + size) * 2);
        }

        Copy(this.data,
                (data) -> VUtil.memcpy(data.getByteBuffer(0, (int) this.bufferSize), byteBuffer, alignedOffset)
        );

        offset = alignedOffset;
        usedBytes = alignedOffset + size;

        //createVertexBuffer(vertexSize, vertexCount, byteBuffer);
    }

    private void resizeBuffer(int newSize) {
        // The old buffer stays alive (addToFreeable) until the command buffers that
        // already reference it have finished; the new buffer keeps the frame's offset.
        MemoryManager.addToFreeable(this);
        createStagingBuffer(newSize);

        System.out.println("resized staging buffer to: " + newSize);
    }

    public void reset() {
        usedBytes = 0;
    }

    public long getOffset() {
        return offset;
    }

    public long getBufferId() {
        return id;
    }
}
