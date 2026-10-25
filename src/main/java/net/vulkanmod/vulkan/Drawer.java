package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.memory.*;
import net.vulkanmod.vulkan.shader.PushConstant;
import net.vulkanmod.vulkan.util.VUtil;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.renderer.vertex.VertexFormat;

import static net.vulkanmod.vulkan.Vulkan.*;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class Drawer {
    private static Drawer INSTANCE = new Drawer();

    private static VkDevice device;
    private static List<VkCommandBuffer> commandBuffers;

    private static Set<Pipeline> usedPipelines = new HashSet<>();

    private VertexBuffer[] vertexBuffers;
    private AutoIndexBuffer quadsIndexBuffer;
    private AutoIndexBuffer triangleFanIndexBuffer;
    private AutoIndexBuffer triangleStripIndexBuffer;
    private UniformBuffers uniformBuffers;

    private static int MAX_FRAMES_IN_FLIGHT;
    private ArrayList<Long> imageAvailableSemaphores;
    private ArrayList<Long> renderFinishedSemaphores;
    private ArrayList<Long> inFlightFences;

    private static int currentFrame = 0;
    private final int commandBuffersCount = getSwapChainFramebuffers().size();
    private boolean[] activeCommandBuffers = new boolean[getSwapChainFramebuffers().size()];

    private static int currentIndex = 0;

    public static Pipeline.BlendState currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
    public static Pipeline.DepthState currentDepthState = Pipeline.DEFAULT_DEPTH_STATE;
    public static Pipeline.LogicOpState currentLogicOpState = Pipeline.DEFAULT_LOGICOP_STATE;
    public static Pipeline.ColorMask currentColorMask = Pipeline.DEFAULT_COLORMASK;

    private static Matrix4f projectionMatrix = new Matrix4f();
    private static Matrix4f modelViewMatrix = new Matrix4f();

    public static boolean shouldRecreate = false;
    public static boolean rebuild = false;
    public static boolean skipRendering = false;

    public Drawer()
    {
        this(2000000, 200000);
    }

    public Drawer(int VBOSize, int UBOSize) {
        device = Vulkan.getDevice();
        MAX_FRAMES_IN_FLIGHT = getSwapChainImages().size();
        vertexBuffers = new VertexBuffer[MAX_FRAMES_IN_FLIGHT];
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
            vertexBuffers[i] = new VertexBuffer(VBOSize, MemoryTypes.HOST_MEM);
        }

        uniformBuffers = new UniformBuffers(UBOSize);
        quadsIndexBuffer = new AutoIndexBuffer(100000, AutoIndexBuffer.DrawType.QUADS);
        triangleFanIndexBuffer = new AutoIndexBuffer(1000, AutoIndexBuffer.DrawType.TRIANGLE_FAN);
        triangleStripIndexBuffer = new AutoIndexBuffer(1000, AutoIndexBuffer.DrawType.TRIANGLE_STRIP);

        createSyncObjects();

        for(boolean bool : activeCommandBuffers) bool = false;

        this.allocateCommandBuffers();
    }

    public void draw(ByteBuffer buffer, int drawMode, VertexFormat vertexFormat, int vertexCount)
    {
        assertCommandBufferState();

        if(!(vertexCount > 0)) return;

        AutoIndexBuffer autoIndexBuffer;
        switch (drawMode) {
            case 7 -> autoIndexBuffer = this.quadsIndexBuffer;
            case 6 -> autoIndexBuffer = this.triangleFanIndexBuffer;
            case 5 -> autoIndexBuffer = this.triangleStripIndexBuffer;
            default -> throw new RuntimeException("unknown drawType");
        }

        autoIndexBuffer.checkCapacity(vertexCount);

        drawAutoIndexed(buffer, this.vertexBuffers[currentFrame], autoIndexBuffer.getIndexBuffer(), drawMode, vertexFormat, vertexCount);
        currentIndex++;

    }

    public void draw(VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int indexCount, int drawMode)
    {
        assertCommandBufferState();

        if(!(indexCount > 0)) return;

        draw(vertexBuffer, indexBuffer, indexCount);
        currentIndex++;

    }

    public void submitDraw()
    {
        if(skipRendering) return;

        drawFrame();
    }

    public void initiateRenderPass() {

        try (MemoryStack stack = stackPush()) {

            IntBuffer width = stack.ints(0);
            IntBuffer height = stack.ints(0);

            SDL_GetWindowSizeInPixels(window, width, height);
            if (width.get(0) == 0 && height.get(0) == 0) {
                skipRendering = true;
            } else {
                skipRendering = false;
            }
        }

        if(skipRendering) return;

        vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);

        CompletableFuture.runAsync(MemoryManager::freeBuffers);
//        MemoryManager.freeBuffers();

        resetDescriptors();

        if (net.vulkanmod.render.TerrainRenderer.BATCH) {
            net.vulkanmod.render.ChunkBatcher.beginFrame();
        }

        vkResetCommandBuffer(commandBuffers.get(currentFrame), 0);

        try(MemoryStack stack = stackPush()) {

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.create();
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.create();
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);

            renderPassInfo.renderPass(getRenderPass());

            VkRect2D renderArea = VkRect2D.create();
            renderArea.offset(VkOffset2D.create().set(0, 0));
            renderArea.extent(getSwapchainExtent());
            renderPassInfo.renderArea(renderArea);

            VkClearValue.Buffer clearValues = VkClearValue.create(2);
            // TODO: revert to black (0,0,0,1) once the world/GUI pipelines are ported;
            // the tint makes the Vulkan-presented frame recognizable during development.
            clearValues.get(0).color().float32(VRenderSystem.clearColor);
            clearValues.get(1).depthStencil().set(1.0f, 0);
            renderPassInfo.pClearValues(clearValues);

            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

            int err = vkBeginCommandBuffer(commandBuffer, beginInfo);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to begin recording command buffer:" + err);
            }

            renderPassInfo.framebuffer(getSwapChainFramebuffers().get(currentFrame));

            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

            VkViewport.Buffer pViewport = Vulkan.viewport(stack);
            vkCmdSetViewport(commandBuffer, 0, pViewport);

            VkRect2D.Buffer pScissor = Vulkan.scissor(stack);
            vkCmdSetScissor(commandBuffer, 0, pScissor);

            vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);

            activeCommandBuffers[currentFrame] = true;
        }
    }

    public void endRenderPass() {
        if(skipRendering) return;

        VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

        vkCmdEndRenderPass(commandBuffer);

        if(captureFile != null) {
            recordCapture(commandBuffer);
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if(result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }

        activeCommandBuffers[currentFrame] = false;
        currentIndex = 0;

        this.vertexBuffers[currentFrame].reset();
        this.uniformBuffers.reset();
        Vulkan.getStagingBuffer(currentFrame).reset();
    }

    private void allocateCommandBuffers() {
        commandBuffers = new ArrayList<>(commandBuffersCount);

        try(MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.create();
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(commandBuffersCount);

            PointerBuffer pCommandBuffers = stack.mallocPointer(commandBuffersCount);

            if (vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers");
            }

            for (int i = 0; i < commandBuffersCount; i++) {
                commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    private static void resetDescriptors() {
        for(Pipeline pipeline : usedPipelines) {
            pipeline.resetDescriptorPool(currentFrame);
        }
        Pipeline.processPendingDescriptorPools();

        usedPipelines.clear();
    }

    private void assertCommandBufferState() {
        if(!activeCommandBuffers[currentFrame]) throw new RuntimeException("CommandBuffer not active.");
    }

    private void createSyncObjects() {

        final int frameNum = getSwapChainImages().size();

        imageAvailableSemaphores = new ArrayList<>(frameNum);
        renderFinishedSemaphores = new ArrayList<>(frameNum);
        inFlightFences = new ArrayList<>(frameNum);

        try(MemoryStack stack = stackPush()) {

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.create();
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.create();
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pImageAvailableSemaphore = stack.mallocLong(1);
            LongBuffer pRenderFinishedSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            for(int i = 0;i < frameNum;i++) {

                if(vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS
                        || vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS
                        || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {

                    throw new RuntimeException("Failed to create synchronization objects for the frame " + i);
                }

                imageAvailableSemaphores.add(pImageAvailableSemaphore.get(0));
                renderFinishedSemaphores.add(pRenderFinishedSemaphore.get(0));
                inFlightFences.add(pFence.get(0));

            }

        }
    }


    public static Drawer getInstance() { return INSTANCE; }

    public static int getCurrentFrame() { return currentFrame; }

    /** True while the command buffer of the current frame is recording. */
    public static boolean isActive() {
        return INSTANCE != null && INSTANCE.activeCommandBuffers[currentFrame];
    }

    // --- Frame capture (autotest/debug): copies the swapchain image of the frame
    // to a host buffer and writes it out as a PNG ---

    private static java.io.File captureFile;
    private static boolean captureRecorded;
    private static long captureBufferId;
    private static long captureBufferMemory;
    private static int captureBufferSize;

    public static void requestCapture(java.io.File file) {
        captureFile = file;
    }

    private void recordCapture(VkCommandBuffer commandBuffer) {
        try(MemoryStack stack = stackPush()) {
            int width = getSwapchainExtent().width();
            int height = getSwapchainExtent().height();
            int size = width * height * 4;

            if(captureBufferId == 0 || captureBufferSize < size) {
                if(captureBufferId != 0) {
                    MemoryManager.freeBuffer(captureBufferId, captureBufferMemory);
                }

                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pMemory = stack.pointers(0L);
                MemoryManager.createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        pBuffer, pMemory);
                captureBufferId = pBuffer.get(0);
                captureBufferMemory = pMemory.get(0);
                captureBufferSize = size;
            }

            long image = getSwapChainImages().get(currentFrame);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.create(1);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            barrier.oldLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(image);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.create(1);
            region.bufferOffset(0);
            region.bufferRowLength(0);
            region.bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            region.imageSubresource().mipLevel(0);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent(VkExtent3D.create().set(width, height, 1));

            vkCmdCopyImageToBuffer(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, captureBufferId, region);

            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            barrier.dstAccessMask(0);

            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, barrier);

            captureRecorded = true;
        }
    }

    private static void writeCapture() {
        java.io.File file = captureFile;
        if(file == null) return;

        int width = getSwapchainExtent().width();
        int height = getSwapchainExtent().height();
        int size = width * height * 4;

        boolean bgra = Vulkan.getSwapChainImageFormat() == VK_FORMAT_B8G8R8A8_UNORM
                || Vulkan.getSwapChainImageFormat() == VK_FORMAT_B8G8R8A8_SRGB;

        MemoryManager.MapAndCopy(captureBufferMemory, size, (data) -> {
            ByteBuffer pixels = data.getByteBuffer(0, size);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for(int y = 0; y < height; ++y) {
                for(int x = 0; x < width; ++x) {
                    int i = (y * width + x) * 4;
                    int r = bgra ? (pixels.get(i + 2) & 0xFF) : (pixels.get(i) & 0xFF);
                    int g = pixels.get(i + 1) & 0xFF;
                    int b = bgra ? (pixels.get(i) & 0xFF) : (pixels.get(i + 2) & 0xFF);
                    image.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
                }
            }

            try {
                ImageIO.write(image, "png", file);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to write capture " + file, e);
            }
        });
    }

    private void drawFrame() {

        try(MemoryStack stack = stackPush()) {

            IntBuffer pImageIndex = stack.mallocInt(1);

            int vkResult = vkAcquireNextImageKHR(device, Vulkan.getSwapChain(), VUtil.UINT64_MAX,
                    imageAvailableSemaphores.get(currentFrame), VK_NULL_HANDLE, pImageIndex);

            if(vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || shouldRecreate) {
                shouldRecreate = false;
                recreateSwapChain();
                return;
            } else if(vkResult != VK_SUCCESS) {
                throw new RuntimeException("Cannot get image: " + vkResult);
            }

            final int imageIndex = pImageIndex.get(0);

            VkSubmitInfo submitInfo = VkSubmitInfo.create();
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(stackGet().longs(imageAvailableSemaphores.get(currentFrame)));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));

            submitInfo.pSignalSemaphores(stackGet().longs(renderFinishedSemaphores.get(currentFrame)));

            submitInfo.pCommandBuffers(stack.pointers(commandBuffers.get(imageIndex)));

            vkResetFences(device, stackGet().longs(inFlightFences.get(currentFrame)));

            Synchronization.waitFences();

            if((vkResult = vkQueueSubmit(getGraphicsQueue(), submitInfo, inFlightFences.get(currentFrame))) != VK_SUCCESS) {
                vkResetFences(device, stackGet().longs(inFlightFences.get(currentFrame)));
                throw new RuntimeException("Failed to submit draw command buffer: " + vkResult);
            }

            if(captureRecorded) {
                vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);
                writeCapture();
                captureRecorded = false;
                captureFile = null;
            }

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.create();
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);

            presentInfo.pWaitSemaphores(stackGet().longs(renderFinishedSemaphores.get(currentFrame)));

            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(getSwapChain()));

            presentInfo.pImageIndices(pImageIndex);

            vkResult = vkQueuePresentKHR(getPresentQueue(), presentInfo);

            if(vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || shouldRecreate) {
                shouldRecreate = false;
                recreateSwapChain();
                return;
            } else if(vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present swap chain image");
            }

            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        }
    }

    private void recreateSwapChain() {
//        for(Long fence : inFlightFences) {
//            vkWaitForFences(device, fence, true, VUtil.UINT64_MAX);
//        }

        vkDeviceWaitIdle(device);

        for(int i = 0; i < getSwapChainImages().size(); ++i) {
            vkDestroyFence(device, inFlightFences.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
            vkDestroySemaphore(device, renderFinishedSemaphores.get(i), null);
        }

        commandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));

        createSyncObjects();

        Vulkan.recreateSwapChain();
        currentFrame = 0;
    }

    public void rebuildInstance() {
        vkDeviceWaitIdle(device);

        Vulkan.recreateSwapChain();
        INSTANCE = new Drawer();
    }

    public static void setProjectionMatrix(Matrix4f mat4) {
        projectionMatrix = mat4;
    }

    public static void setModelViewMatrix(Matrix4f mat4) {
        modelViewMatrix = mat4;
    }

    public static Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    public static Matrix4f getModelViewMatrix() {
        return modelViewMatrix;
    }

    public AutoIndexBuffer getQuadsIndexBuffer() {
        return quadsIndexBuffer;
    }

    public AutoIndexBuffer getTriangleFanIndexBuffer() {
        return triangleFanIndexBuffer;
    }

    private void draw(VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int indexCount) {

        Pipeline boundPipeline = VRenderSystem.getShaderPipeline();
        usedPipelines.add(boundPipeline);
        bindPipeline(boundPipeline);

        uploadAndBindUBOs(boundPipeline);

        drawIndexed(vertexBuffer, indexBuffer, indexCount);
    }

    private void drawAutoIndexed(ByteBuffer buffer, VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int drawMode, VertexFormat vertexFormat, int vertexCount) {
        vertexBuffer.copyToVertexBuffer(vertexFormat.getSize(), vertexCount, buffer);

        Pipeline boundPipeline = VRenderSystem.getShaderPipeline();
        usedPipelines.add(boundPipeline);
        bindPipeline(boundPipeline);

        uploadAndBindUBOs(boundPipeline);

        int indexCount;
        if(drawMode == 7) indexCount = vertexCount * 3 / 2;
        else if(drawMode == 6 || drawMode == 5) indexCount = (vertexCount - 2) * 3;
        else throw new RuntimeException("unknown drawMode: " + drawMode);

        drawIndexed(vertexBuffer, indexBuffer, indexCount);
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {

        try(MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

            //long uniformBufferOffset = this.uniformBuffers.getUsedBytes();
            long descriptorSet = pipeline.createDescriptorSets(commandBuffer, currentFrame, uniformBuffers);

            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipeline.getLayout() , 0, stack.longs(descriptorSet), null);
        }
    }

    public void drawIndexed(VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int indexCount) {

        try(MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

            LongBuffer vertexBuffers = stack.longs(vertexBuffer.getId());
            LongBuffer offsets = stack.longs(vertexBuffer.getOffset());
//            Profiler.Push("bindVertex");
            vkCmdBindVertexBuffers(commandBuffer, 0, vertexBuffers, offsets);

            vkCmdBindIndexBuffer(commandBuffer, indexBuffer.getId(), indexBuffer.getOffset(), VK_INDEX_TYPE_UINT16);
//            Profiler.Push("draw");
            vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0);
        }
    }

    /**
     * Issues one indirect draw covering many chunk sections at once. All sections share the
     * arena vertex buffer (bound at offset 0; each indirect command selects its section via
     * {@code vertexOffset}) and the shared quad index buffer. The descriptor set must already
     * be bound (see {@link #uploadAndBindUBOs}).
     */
    public void drawIndexedIndirect(long vertexBuffer, long indexBuffer, long indexOffset,
                                    long indirectBuffer, long indirectOffset, int drawCount) {
        if (drawCount <= 0) {
            return;
        }
        try(MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffer), stack.longs(0L));
            vkCmdBindIndexBuffer(commandBuffer, indexBuffer, indexOffset, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffer, indirectOffset, drawCount, 20);
        }
    }

    public void bindPipeline(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

        currentDepthState = VRenderSystem.getDepthState();
        currentColorMask = new Pipeline.ColorMask(VRenderSystem.getColorMask());
        Pipeline.PipelineState currentState = new Pipeline.PipelineState(currentBlendState, currentDepthState, currentLogicOpState, currentColorMask);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(currentState));

        usedPipelines.add(pipeline);
    }

    public void pushConstants(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

        PushConstant pushConstant = pipeline.getPushConstant();
        pushConstant.update();
        vkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstant.getBuffer());
    }

    public static void setDepthBias(float units, float factor) {
        VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

        vkCmdSetDepthBias(commandBuffer, units, 0.0f, factor);
    }

    public static void clearAttachments(int v) {
        if(skipRendering) return;

        VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

        try(MemoryStack stack = stackPush()) {
            //ClearValues have to be different for each attachment to clear, it seems it works like a buffer: color and depth attributes override themselves
            VkClearValue colorValue = VkClearValue.create();
            colorValue.color().float32(VRenderSystem.clearColor);

            VkClearValue depthValue = VkClearValue.create();
            depthValue.depthStencil().depth(VRenderSystem.clearDepth);

            int attachmentsCount;
            VkClearAttachment.Buffer pAttachments;
            if (v == 0x100) {
                attachmentsCount = 1;

                pAttachments = VkClearAttachment.create(attachmentsCount);

                VkClearAttachment clearDepth = pAttachments.get(0);
                clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                clearDepth.clearValue(depthValue);
            } else if (v == 0x4000) {
                attachmentsCount = 1;

                pAttachments = VkClearAttachment.create(attachmentsCount);

                VkClearAttachment clearColor = pAttachments.get(0);
                clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                clearColor.colorAttachment(0);
                clearColor.clearValue(colorValue);
            } else if (v == 0x4100) {
                attachmentsCount = 2;

                pAttachments = VkClearAttachment.create(attachmentsCount);

                VkClearAttachment clearColor = pAttachments.get(0);
                clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                clearColor.clearValue(colorValue);

                VkClearAttachment clearDepth = pAttachments.get(1);
                clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                clearDepth.clearValue(depthValue);
            } else {
                throw new RuntimeException("unexpected value");
            }

            //Rect to clear
            VkRect2D renderArea = VkRect2D.create();
            renderArea.offset(VkOffset2D.create().set(0, 0));
            renderArea.extent(getSwapchainExtent());

            VkClearRect.Buffer pRect = VkClearRect.create(1);
            pRect.get(0).rect(renderArea);
            pRect.get(0).layerCount(1);

            vkCmdClearAttachments(commandBuffer, pAttachments, pRect);
        }
    }

    public static void setViewport(int x, int y, int width, int height) {

        try(MemoryStack stack = stackPush()) {
            VkViewport.Buffer viewport = VkViewport.create(1);
            viewport.x(x);
            viewport.y(height + y);
            viewport.width(width);
            viewport.height(-height);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);

            vkCmdSetViewport(commandBuffers.get(currentFrame), 0, viewport);
        }

    }

    public static void pushDebugSection(String s) {
//        VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);
//
//        try(MemoryStack stack = stackPush()) {
//            VkDebugUtilsLabelEXT markerInfo = VkDebugUtilsLabelEXT.create();
//            markerInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT);
//            ByteBuffer string = stack.UTF8(s);
//            markerInfo.pLabelName(string);
//            vkCmdBeginDebugUtilsLabelEXT(commandBuffer, markerInfo);
//        }
    }

    public static void popDebugSection() {
//        VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);
//
//        vkCmdEndDebugUtilsLabelEXT(commandBuffer);
    }

    public static void popPushDebugSection(String s) {
        popDebugSection();
        pushDebugSection(s);
    }
}
