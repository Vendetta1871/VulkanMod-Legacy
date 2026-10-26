package net.vulkanmod.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import net.vulkanmod.vulkan.util.VUtil;

import static net.vulkanmod.vulkan.Vulkan.findQueueFamilies;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class TransferQueue {
    private static final VkDevice device = Vulkan.getDevice();

    private static long commandPool;
    private static final List<CommandBuffer> commandBuffers = new ArrayList<>();
    private static final List<Long> fences = new ArrayList<>();
    private static int current = 0;

    private static CommandBuffer currentCmdBuffer;

    static {
        createCommandPool();
    }

    private static void createCommandPool() {

        try(MemoryStack stack = stackPush()) {

            Vulkan.QueueFamilyIndices queueFamilyIndices = findQueueFamilies(device.getPhysicalDevice());

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.create();
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(queueFamilyIndices.graphicsFamily);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.mallocLong(1);

            if (vkCreateCommandPool(device, poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }

            commandPool = pCommandPool.get(0);
        }
    }

    public static void startRecording() {
        currentCmdBuffer = beginCommands();
    }

    public static void endRecording() {
        endCommands(currentCmdBuffer);
        currentCmdBuffer = null;
    }

    public static CommandBuffer getCommandBuffer() {
        if (currentCmdBuffer != null) {
            return currentCmdBuffer;
        } else {
            return beginCommands();
        }
    }

    public static void endIfNeeded(CommandBuffer commandBuffer) {
        if (currentCmdBuffer == null) {
            endCommands(commandBuffer);
        }
    }

    public synchronized static CommandBuffer beginCommands() {

        try(MemoryStack stack = stackPush()) {
            final int size = 1;

            CommandBuffer commandBuffer = new CommandBuffer();

            if(current >= commandBuffers.size()) {

                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.create();
                allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
                allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
                allocInfo.commandPool(commandPool);
                allocInfo.commandBufferCount(size);

                PointerBuffer pCommandBuffer = stack.mallocPointer(size);
                vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer);

                for(int i = 0; i < size; ++i) {
                    commandBuffer.handle = new VkCommandBuffer(pCommandBuffer.get(i), device);
                    commandBuffers.add(commandBuffer);
                }


                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.create();
                fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
                fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

                LongBuffer pFence = stack.mallocLong(size);
                vkCreateFence(device, fenceInfo, null, pFence);

                for(int i = 0; i < size; ++i) {
                    fences.add(pFence.get(0));
                    commandBuffer.fence = pFence.get(0);
                }

            }

            commandBuffer = commandBuffers.get(current);

            // Recycling a pool slot whose submit has not retired yet would record into
            // a running command buffer. Waiting here is the natural backpressure for
            // uploads issued outside the frame window; inside it copies go to the
            // frame's transfer command buffer (see Drawer.getFrameTransferCmd) and this
            // path is barely used.
            commandBuffer.awaitFence();

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            int err = vkBeginCommandBuffer(commandBuffer.handle, beginInfo);
            if(err != VK_SUCCESS) {
                throw new RuntimeException("Failed to begin transfer command buffer: " + err);
            }

            current++;

            return commandBuffer;
        }
    }

    public synchronized static void endCommands(CommandBuffer commandBuffer) {

        try(MemoryStack stack = stackPush()) {

            int err = vkEndCommandBuffer(commandBuffer.handle);
            if(err != VK_SUCCESS) {
                throw new RuntimeException("Failed to end transfer command buffer: " + err);
            }

            vkResetFences(device, commandBuffer.fence);

            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(commandBuffer.handle));

            int result = vkQueueSubmit(Vulkan.getGraphicsQueue(), submitInfo, commandBuffer.fence);
            if(result != VK_SUCCESS) {
                // Leave the fence unpending: nothing signals it, so nobody may wait on it.
                throw new RuntimeException("Failed to submit transfer command buffer: " + result);
            }
            commandBuffer.fencePending = true;
        }
    }

    /**
     * Blocks until every transfer command buffer submitted through this queue has
     * retired. Used by the staging-buffer guard, the swapchain recreation and the
     * minimized-window maintenance, where no frame fence covers the copies.
     */
    public synchronized static void waitPendingFences() {
        for(CommandBuffer commandBuffer : commandBuffers) {
            commandBuffer.awaitFence();
        }
    }

    public static long getCommandPool() {
        return commandPool;
    }

    public void resetCommandPool() {
        vkResetCommandPool(device, commandPool, 0);
    }

    public static void resetCurrent() {
        current = 0;
    }

    public static void cleanUp() {
        for(long fence : fences) {
            vkDestroyFence(device, fence, null);
        }
    }

    public static class CommandBuffer {
        VkCommandBuffer handle;
        long fence;
        /** True from a successful {@link #endCommands} until the fence is waited on. */
        boolean fencePending;

        public VkCommandBuffer getHandle() {
            return handle;
        }

        public long getFence() {
            return fence;
        }

        /** Waits (at most once) for the submit of this command buffer to retire. */
        void awaitFence() {
            if(fencePending) {
                vkWaitForFences(device, fence, true, VUtil.UINT64_MAX);
                fencePending = false;
            }
        }
    }
}
