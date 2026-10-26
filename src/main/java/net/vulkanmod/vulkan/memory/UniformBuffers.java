package net.vulkanmod.vulkan.memory;

import net.vulkanmod.vulkan.util.VUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.deviceProperties;
import static net.vulkanmod.vulkan.Vulkan.getSwapChainImages;
import static net.vulkanmod.vulkan.memory.MemoryManager.Copy;
import static net.vulkanmod.vulkan.util.VUtil.align;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;

public class UniformBuffers {

    private int bufferSize;
    private int usedBytes;

    // Bumped whenever the per-frame buffers are replaced (a resize creates new VkBuffers).
    // The descriptor-set caches key on it: sets written against the old buffers must not
    // be reused after a resize.
    private int generation;

    // Bumped by reset() at the start of every frame; identifies the window during which
    // uploaded UBO offsets keep their bytes (H10 offset reuse).
    private long resetCount;

    private List<UniformBuffer> uniformBuffers;

    private final static int minOffset = (int) deviceProperties.limits().minUniformBufferOffsetAlignment();
    private final int imagesSize = getSwapChainImages().size();

    public UniformBuffers(int size) {
        createUniformBuffers(size);
    }

    private void createUniformBuffers(int size) {
        this.bufferSize = size;

        uniformBuffers = new ArrayList<>(imagesSize);

        for(int i = 0; i < imagesSize; ++i) {
            uniformBuffers.add(new UniformBuffer(this.bufferSize));
        }
    }

    /**
     * Copies {@code buffer} (position 0, remaining = data size) into the frame slot's
     * ring at the next aligned offset and returns that offset. The caller passes it as
     * the dynamic offset when binding the descriptor set, so the UBO data needs no
     * per-draw descriptor write.
     */
    /** The ring slot an upload of {@code size} bytes will occupy. */
    public int alignedSize(int size) {
        return align(size, minOffset);
    }

    /**
     * Ensures the ring can hold {@code bytes} more without resizing in the middle of
     * a multi-UBO upload (see Pipeline#bindDescriptorSets, N6).
     */
    public void ensureCapacity(int bytes) {
        if (bytes > this.bufferSize - this.usedBytes) {
            resizeBuffer((this.bufferSize + bytes) * 2);
        }
    }

    public int uploadUBO(ByteBuffer buffer, int frame) {
        int size = buffer.remaining();
        int alignedSize = align(size, minOffset);
        if (alignedSize > this.bufferSize - this.usedBytes) {
            resizeBuffer((int) ((this.bufferSize + alignedSize) * 2));
        }

        int offset = usedBytes;
        uniformBuffers.get(frame).uploadUBO(buffer, offset);
        usedBytes += alignedSize;
        return offset;
    }

    private void resizeBuffer(int newSize) {

        for (UniformBuffer uniformBuffer : uniformBuffers) {
            uniformBuffer.resizeBuffer(newSize);
        }

        this.bufferSize = newSize;
        ++generation;

        System.out.println("resized UniformBuffer to: " + newSize);
    }

    public int getGeneration() {
        return generation;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void reset() {
        usedBytes = 0;
        // Identifies the frame window for UBO offset reuse (H10): uploaded offsets keep
        // their bytes until this reset, and the frame-slot index alone repeats every
        // three frames.
        ++resetCount;
    }

    public long getResetCount() {
        return resetCount;
    }

    public int getUsedBytes() {
        return usedBytes;
    }

    public long getId(int i) {
        return uniformBuffers.get(i).getId();
    }

    static class UniformBuffer extends Buffer {

        protected UniformBuffer(int size) {
            super(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, MemoryTypes.HOST_MEM);
            this.createBuffer(size);
        }

        public void uploadUBO(ByteBuffer buffer, int offset) {
            Copy(this.data ,(data) -> VUtil.memcpy(data.getByteBuffer(0, (int) bufferSize), buffer, offset));
        }

        private void resizeBuffer(int newSize) {
            MemoryManager.addToFreeable(this);
            createBuffer(newSize);
        }
    }

}
