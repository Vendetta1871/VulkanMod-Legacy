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

import net.minecraft.client.renderer.vertex.VertexFormat;

import static net.vulkanmod.vulkan.Vulkan.*;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class Drawer {
    private static Drawer INSTANCE;

    private static VkDevice device;
    private static List<VkCommandBuffer> commandBuffers;

    /**
     * Per-frame-slot command buffer collecting every transfer copy issued while the
     * frame is being recorded (chunk/arena uploads, texture uploads). It is submitted
     * together with the frame's render command buffer in one vkQueueSubmit, so all
     * copies of a frame cost a single submit and are ordered before its draws by the
     * implicit intra-submit dependency, closed by one trailing global barrier.
     */
    private static List<VkCommandBuffer> transferCommandBuffers;
    private boolean frameTransferRecording;

    /**
     * Per frame slot: pipelines that allocated descriptor sets from that slot's pools since
     * it was last reset (N4). Sets come from descriptorPools[currentFrame] only, so a slot's
     * pools are reset exactly for the pipelines that used that slot.
     */
    private static List<Set<Pipeline>> usedPipelines;

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
    private final int commandBuffersCount = getSwapChainImages().size();
    private boolean[] activeCommandBuffers = new boolean[getSwapChainImages().size()];

    /**
     * Swapchain image acquired for the frame currently being recorded. Command buffers,
     * fences and semaphores are indexed by {@link #currentFrame}; framebuffers and the
     * swapchain image used by a capture are indexed by this field (they only coincide
     * when the presentation engine hands out images in order).
     */
    private int imageIndex = 0;

    /**
     * True from a successful {@code vkQueueSubmit} of the frame until its fence has been
     * waited on. Guards the wait in {@link #initiateRenderPass()} so a failed submit
     * (whose fence will never be signaled) cannot hang the next use of the frame slot.
     */
    private final boolean[] fencePending = new boolean[getSwapChainImages().size()];

    private static int currentIndex = 0;

    public static Pipeline.BlendState currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
    public static Pipeline.LogicOpState currentLogicOpState = Pipeline.DEFAULT_LOGICOP_STATE;

    private static Matrix4f projectionMatrix = new Matrix4f();
    private static Matrix4f modelViewMatrix = new Matrix4f();

    public static boolean shouldRecreate = false;
    public static boolean rebuild = false;
    public static boolean skipRendering = false;

    // Set when an acquire succeeded but the swapchain must go once the frame it
    // rendered is presented (N10: the acquire's semaphore signal has to be
    // consumed by a submit before the swapchain cycle may touch the sync objects).
    private boolean recreateAfterPresent;

    /**
     * True from a successful vkAcquireNextImageKHR until the submit that waits on
     * imageAvailable[currentFrame] succeeded. A throw in between leaves it set, so the
     * retry renders into the same imageIndex instead of acquiring again on a semaphore
     * with a pending signal (R9, VUID-vkAcquireNextImageKHR-semaphore-01286).
     */
    private boolean imageAcquired;

    public Drawer()
    {
        this(2000000, 200000);
    }

    public Drawer(int VBOSize, int UBOSize) {
        INSTANCE = this;
        device = Vulkan.getDevice();
        MAX_FRAMES_IN_FLIGHT = getSwapChainImages().size();
        usedPipelines = new ArrayList<>(MAX_FRAMES_IN_FLIGHT);
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
            usedPipelines.add(new HashSet<>());
        }
        vertexBuffers = new VertexBuffer[MAX_FRAMES_IN_FLIGHT];
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
            vertexBuffers[i] = new VertexBuffer(VBOSize, MemoryTypes.HOST_MEM);
        }

        uniformBuffers = new UniformBuffers(UBOSize);
        quadsIndexBuffer = new AutoIndexBuffer(100000, AutoIndexBuffer.DrawType.QUADS);
        triangleFanIndexBuffer = new AutoIndexBuffer(1000, AutoIndexBuffer.DrawType.TRIANGLE_FAN);
        triangleStripIndexBuffer = new AutoIndexBuffer(1000, AutoIndexBuffer.DrawType.TRIANGLE_STRIP);

        createSyncObjects();

        java.util.Arrays.fill(activeCommandBuffers, false);

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

        drawAutoIndexed(buffer, this.vertexBuffers[currentFrame], autoIndexBuffer.getIndexBuffer(vertexCount),
                drawMode, vertexFormat, vertexCount);
        currentIndex++;

    }

    /**
     * Non-indexed line draw for the debug-box / selection-box capture (GL_LINES,
     * GL_LINE_STRIP). The caller sets {@link VRenderSystem#topology} to the matching line
     * topology and binds the POSITION_COLOR line pipeline; the width is taken from
     * {@link VRenderSystem#getLineWidth()} through the pipeline's dynamic line-width state.
     * Vertices are drawn in order, so no index buffer is needed.
     */
    public void drawLines(ByteBuffer buffer, VertexFormat vertexFormat, int vertexCount)
    {
        assertCommandBufferState();

        if(!(vertexCount > 0)) return;

        VertexBuffer vertexBuffer = this.vertexBuffers[currentFrame];
        vertexBuffer.copyToVertexBuffer(vertexFormat.getSize(), vertexCount, buffer);

        Pipeline boundPipeline = VRenderSystem.getShaderPipeline();
        bindPipeline(boundPipeline);

        uploadAndBindUBOs(boundPipeline);

        try(MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

            // The dragon's healing beams also come through here as a triangle strip; only
            // line pipelines declare the dynamic line width (VUID-vkCmdDraw-None-08608).
            int topology = VRenderSystem.topology;
            if (topology == VK_PRIMITIVE_TOPOLOGY_LINE_LIST || topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP) {
                vkCmdSetLineWidth(commandBuffer, VRenderSystem.getLineWidth());
            }
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffer.getId()), stack.longs(vertexBuffer.getOffset()));
            vkCmdDraw(commandBuffer, vertexCount, 1, 0, 0);
        }
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

        if(skipRendering) {
            // Minimized: no frames are rendered, but the tick loop keeps uploading
            // animated tiles and the renderer keeps deferring frees. Retire everything
            // pending and reset the frame-slot buffers so nothing accumulates until
            // the window is shown again.
            TransferQueue.waitPendingFences();
            waitForAllFrameFences();
            MemoryManager.freeBuffers();
            TransferQueue.resetCurrent();

            resetFrameBuffers(currentFrame);
            return;
        }

        if(imageAcquired) {
            // R9: an earlier call acquired imageIndex and threw before the frame was
            // submitted. The image and the pending signal of imageAvailable[currentFrame]
            // are still ours; the fence wait, the rewinds and the frees already ran, so
            // this call skips straight to recording and reuses imageIndex.
            recreateAfterPresent |= shouldRecreate;
            shouldRecreate = false;
        } else {
            // Only wait on a fence that a successful submit will signal (see fencePending).
            if(fencePending[currentFrame]) {
                vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);
            }
            fencePending[currentFrame] = false;

            // Uploads issued between frames (animated tiles in runTick etc.) submit through
            // the TransferQueue; the frame fence waited on above does not cover those
            // submits. Retire them before rewinding the staging slot, or the next in-frame
            // upload would overwrite bytes the GPU copy has not read yet (the minimized
            // path below already does the same). No-op when nothing is pending.
            TransferQueue.waitPendingFences();

            // The frame fence just waited on covers every copy submitted with the previous
            // occupant of this frame slot (staging included), so the CPU-side allocators
            // can be rewound here. Doing it at frame start instead of frame end also keeps
            // the staging buffer safe while minimized (endRenderPass() is skipped then).
            resetFrameBuffers(currentFrame);

            // A frame abandoned between the begin and the end of its transfer command
            // buffer (submit failure) would leave the flag set; the buffer itself is reset
            // on its next lazy use.
            frameTransferRecording = false;

            // TransferQueue pool slots are recycled by index; rewinding it once per frame
            // bounds the pool to the uploads issued between two frames. beginCommands()
            // still waits on a slot's own fence before reuse, so this is bookkeeping only.
            TransferQueue.resetCurrent();

            // Synchronous: freeBuffers mutates the MemoryManager handle maps that the
            // render thread writes on every chunk upload, so it must not run on a pool
            // thread (N5). It is cheap - it only frees entries whose frame boundary has
            // already retired.
            MemoryManager.freeBuffers();

            // R10: a failed vkCreateSwapchainKHR retired the old swapchain; acquiring from
            // a retired swapchain is invalid (VUID-vkAcquireNextImageKHR-swapchain-01285).
            // Retry the creation and drop the frame, exactly like the OUT_OF_DATE path
            // below (nothing is acquired yet, so no semaphore is involved).
            if(Vulkan.isSwapchainRetired()) {
                recreateSwapChain();
                skipRendering = true;
                return;
            }

            // Acquire the swapchain image before recording so the frame is rendered into the
            // image that is later submitted and presented. The acquired index differs from
            // currentFrame as soon as the presentation engine hands images out of order
            // (IMMEDIATE/MAILBOX, or after a swapchain recreation).
            try(MemoryStack stack = stackPush()) {

                IntBuffer pImageIndex = stack.mallocInt(1);

                int vkResult = vkAcquireNextImageKHR(device, Vulkan.getSwapChain(), VUtil.UINT64_MAX,
                        imageAvailableSemaphores.get(currentFrame), VK_NULL_HANDLE, pImageIndex);

                if(vkResult == VK_ERROR_OUT_OF_DATE_KHR) {
                    // No image was acquired. Recreate before the next acquire and drop this
                    // frame; the frame-slot sync objects survive the recreation (they are
                    // not swapchain objects), so the implementation-defined signal of a
                    // failed acquire can never dangle on a semaphore destroy (N10).
                    recreateSwapChain();
                    // The frame is dropped: nothing was recorded yet, endRenderPass()
                    // and submitDraw() bail out on skipRendering.
                    skipRendering = true;
                    return;
                } else if(vkResult != VK_SUCCESS) {
                    throw new RuntimeException("Cannot get image: " + vkResult);
                }

                imageIndex = pImageIndex.get(0);
                imageAcquired = true;

                // SUBOPTIMAL or a pending resize request: the acquire already signaled the
                // semaphore, so this frame must be rendered and presented before the
                // recreation. Dropping it here would leave that signal pending when the
                // semaphore is destroyed or re-acquired (N10).
                recreateAfterPresent = shouldRecreate || vkResult == VK_SUBOPTIMAL_KHR;
                shouldRecreate = false;
            }
        }

        resetDescriptors();

        if (net.vulkanmod.render.TerrainRenderer.BATCH) {
            net.vulkanmod.render.ChunkBatcher.beginFrame();
        }

        vkResetCommandBuffer(commandBuffers.get(currentFrame), 0);
        // The command buffer holds no binds anymore: the redundant-bind cache is stale.
        lastBindFrame = -1;

        try(MemoryStack stack = stackPush()) {

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

            int err = vkBeginCommandBuffer(commandBuffer, beginInfo);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to begin recording command buffer:" + err);
            }

            // B2/B3: the world renders into the scene target; the hand and GUI switch to
            // the display target (enterDisplayPhase); endRenderPass() copies the result
            // into the acquired swapchain image. Viewport and scissor follow the target.
            float[] clear = new float[4];
            VRenderSystem.clearColor.get(0, clear);
            FrameTargets.beginFrame(commandBuffer, clear, stack);

            vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);

            activeCommandBuffers[currentFrame] = true;
        }
    }

    public void endRenderPass() {
        if(skipRendering) return;

        VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

        try(MemoryStack stack = stackPush()) {
            VkExtent2D extent = getSwapchainExtent();
            FrameTargets.endFrame(commandBuffer, getSwapChainImages().get(imageIndex),
                    Vulkan.getSwapChainImageFormat(), extent.width(), extent.height(), stack);
        }

        if(captureFile != null) {
            recordCapture(commandBuffer);
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if(result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }

        activeCommandBuffers[currentFrame] = false;
        currentIndex = 0;
    }

    /**
     * Rewinds the CPU-side allocators of one frame slot. Only called after the slot's
     * frame fence has been waited on (see {@link #initiateRenderPass()}), so no submit
     * can still be reading what these buffers hold.
     */
    private void resetFrameBuffers(int slot) {
        this.vertexBuffers[slot].reset();
        this.uniformBuffers.reset();
        Vulkan.getStagingBuffer(slot).reset();
    }

    private void allocateCommandBuffers() {
        commandBuffers = new ArrayList<>(commandBuffersCount);
        transferCommandBuffers = new ArrayList<>(commandBuffersCount);

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

            if (vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate transfer command buffers");
            }

            for (int i = 0; i < commandBuffersCount; i++) {
                transferCommandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    /**
     * The transfer command buffer of the frame being recorded, begun on first use, or
     * null when called outside the frame window (the caller then submits its copies
     * through {@link TransferQueue} with a fence of their own).
     */
    public static VkCommandBuffer getFrameTransferCmd() {
        Drawer drawer = INSTANCE;
        if(drawer == null || !drawer.activeCommandBuffers[currentFrame]) {
            return null;
        }

        VkCommandBuffer commandBuffer = drawer.transferCommandBuffers.get(currentFrame);

        if(!drawer.frameTransferRecording) {
            vkResetCommandBuffer(commandBuffer, 0);

            try(MemoryStack stack = stackPush()) {
                VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
                beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

                int err = vkBeginCommandBuffer(commandBuffer, beginInfo);
                if(err != VK_SUCCESS) {
                    throw new RuntimeException("Failed to begin frame transfer command buffer: " + err);
                }
            }

            drawer.frameTransferRecording = true;
        }

        return commandBuffer;
    }

    /**
     * Blocks until nothing can still read the staging buffer of the slot the next
     * upload would write ({@code currentFrame}). Copies recorded inside the frame
     * window are submitted with the frame and covered by its fence; copies submitted
     * through the TransferQueue need their own wait. Callers are the staging-buffer
     * writes issued outside the frame window, where no frame fence was waited on.
     */
    public static void ensureStagingFree() {
        Drawer drawer = INSTANCE;
        if(drawer == null) {
            return;
        }

        TransferQueue.waitPendingFences();
        drawer.waitForFrameFence(currentFrame);
    }

    private void waitForFrameFence(int slot) {
        if(fencePending[slot]) {
            vkWaitForFences(device, inFlightFences.get(slot), true, VUtil.UINT64_MAX);
            fencePending[slot] = false;
        }
    }

    private void waitForAllFrameFences() {
        for(int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
            waitForFrameFence(i);
        }
    }

    /**
     * Blocks until every frame slot's fence has retired. Used by the world-change arena
     * reset (M22): the last submitted frames may still be reading the chunk arena through
     * their indirect draws, and the new world's uploads start from offset 0 again.
     */
    public static void waitForAllFrames() {
        Drawer drawer = INSTANCE;
        if(drawer == null) {
            return;
        }
        drawer.waitForAllFrameFences();
    }

    private static void resetDescriptors() {
        Set<Pipeline> used = usedPipelines.get(currentFrame);
        for(Pipeline pipeline : used) {
            pipeline.resetDescriptorPool(currentFrame);
        }
        Pipeline.processPendingDescriptorPools();

        used.clear();
    }

    private void assertCommandBufferState() {
        if(!activeCommandBuffers[currentFrame]) throw new RuntimeException("CommandBuffer not active.");
    }

    private void createSyncObjects() {

        // imageAvailable and the fences are indexed by currentFrame and cycle over
        // MAX_FRAMES_IN_FLIGHT even after a recreation changed the number of images,
        // so they are sized by the frame-slot count. renderFinished is indexed by
        // imageIndex: its present consumes the signal, and the presentation engine
        // only hands the same image out again after that present retired, so a
        // per-slot semaphore could be re-signaled while still in use under
        // MAILBOX/IMMEDIATE (N12, VUID-vkQueueSubmit-pSignalSemaphores-00067).
        final int frameNum = MAX_FRAMES_IN_FLIGHT;

        imageAvailableSemaphores = new ArrayList<>(frameNum);
        inFlightFences = new ArrayList<>(frameNum);

        try(MemoryStack stack = stackPush()) {

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.create();
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.create();
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pImageAvailableSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            for(int i = 0;i < frameNum;i++) {

                if(vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS
                        || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {

                    throw new RuntimeException("Failed to create synchronization objects for the frame " + i);
                }

                imageAvailableSemaphores.add(pImageAvailableSemaphore.get(0));
                inFlightFences.add(pFence.get(0));

            }

        }

        recreateRenderFinishedSemaphores();
    }


    public static Drawer getInstance() { return INSTANCE; }

    /**
     * Switches the frame from the scene target to the display target (B3). Called where
     * vanilla starts the hand and the GUI; a no-op once switched or outside a frame.
     */
    public static void enterDisplayPhase() {
        if(!isActive() || FrameTargets.phase() != FrameTargets.Phase.SCENE) return;
        try(MemoryStack stack = stackPush()) {
            FrameTargets.enterDisplayPhase(commandBuffers.get(currentFrame), stack);
        }
    }

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

            long image = getSwapChainImages().get(imageIndex);

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

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
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

            // Close the frame's transfer command buffer. A single trailing global
            // barrier makes all its writes available and visible to every read stage
            // of the render commands (stage masks alone do not flush caches - RADV
            // flushes by access mask, so vertex/index/indirect data could read stale);
            // the two command buffers share one submit, so the copies are guaranteed
            // to run first by the intra-submit implicit dependency.
            VkCommandBuffer transferCmd = null;
            if(frameTransferRecording) {
                frameTransferRecording = false;

                transferCmd = transferCommandBuffers.get(currentFrame);

                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
                barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT
                        | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT
                        | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT
                        | VK_ACCESS_TRANSFER_WRITE_BIT);

                vkCmdPipelineBarrier(transferCmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT
                        | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                        | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                        0, barrier, (VkBufferMemoryBarrier.Buffer) null,
                        (VkImageMemoryBarrier.Buffer) null);

                int err = vkEndCommandBuffer(transferCmd);
                if(err != VK_SUCCESS) {
                    throw new RuntimeException("Failed to end frame transfer command buffer: " + err);
                }
            }

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(stackGet().longs(imageAvailableSemaphores.get(currentFrame)));
            // The swapchain image is first touched by the copy at the end of the frame
            // (FrameTargets.endFrame), so rendering does not wait for the acquire.
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT));

            submitInfo.pSignalSemaphores(stackGet().longs(renderFinishedSemaphores.get(imageIndex)));

            // The command buffer recorded in initiateRenderPass() for this frame slot;
            // it renders into the framebuffer of the acquired imageIndex. The frame's
            // transfer copies (if any) precede it in the same submit.
            VkCommandBuffer frameCmd = commandBuffers.get(currentFrame);
            submitInfo.pCommandBuffers(transferCmd != null
                    ? stack.pointers(transferCmd.address(), frameCmd.address())
                    : stack.pointers(frameCmd));

            vkResetFences(device, stackGet().longs(inFlightFences.get(currentFrame)));

            int vkResult = vkQueueSubmit(getGraphicsQueue(), submitInfo, inFlightFences.get(currentFrame));
            if(vkResult != VK_SUCCESS) {
                // The fence will never be signaled; fencePending keeps the next use of
                // this frame slot from waiting on it forever.
                throw new RuntimeException("Failed to submit draw command buffer: " + vkResult);
            }
            fencePending[currentFrame] = true;
            // The submit consumed the acquire's semaphore signal: the next frame acquires (R9).
            imageAcquired = false;

            if(captureRecorded) {
                vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);
                writeCapture();
                captureRecorded = false;
                captureFile = null;
            }

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);

            presentInfo.pWaitSemaphores(stackGet().longs(renderFinishedSemaphores.get(imageIndex)));

            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(getSwapChain()));

            presentInfo.pImageIndices(stack.ints(imageIndex));

            vkResult = vkQueuePresentKHR(getPresentQueue(), presentInfo);

            if(vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || shouldRecreate) {
                recreateSwapChain();
                return;
            } else if(vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present swap chain image");
            }

            if(recreateAfterPresent) {
                // The frame an earlier SUBOPTIMAL acquire or resize request forced us
                // to render: presented now, so recreate here (N10).
                recreateSwapChain();
                return;
            }

            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        }
    }

    /** Destroys the per-frame sync objects; used by the shutdown teardown in Vulkan.cleanUp. */
    public void cleanUpSyncObjects() {
        if(inFlightFences == null) {
            return;
        }
        for(int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
            vkDestroyFence(device, inFlightFences.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
        }
        for(long semaphore : renderFinishedSemaphores) {
            vkDestroySemaphore(device, semaphore, null);
        }
        inFlightFences = null;
    }

    /** @return true when the swapchain was replaced. */
    private boolean recreateSwapChain() {
        vkDeviceWaitIdle(device);
        shouldRecreate = false;

        if(!Vulkan.recreateSwapChain()) {
            // Minimized surface (zero currentExtent): creating a swapchain for it would
            // fail every frame. Keep the old one and retry once the window has a size.
            shouldRecreate = true;
            return false;
        }

        // The frame-slot sync objects (imageAvailable, fences) are not swapchain
        // objects and are indexed by currentFrame, so they survive the recreation:
        // destroying them here could hit a semaphore the failed or dropped acquire
        // left signaled (N10). Only the per-image renderFinished set follows the
        // swapchain image count; vkDeviceWaitIdle above proved none is in use.
        recreateRenderFinishedSemaphores();

        commandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));
        transferCommandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));

        java.util.Arrays.fill(activeCommandBuffers, false);
        java.util.Arrays.fill(fencePending, false);
        frameTransferRecording = false;
        recreateAfterPresent = false;

        currentFrame = 0;
        imageIndex = 0;
        return true;
    }

    /** One render-finished semaphore per swapchain image (N12); see createSyncObjects. */
    private void recreateRenderFinishedSemaphores() {
        if(renderFinishedSemaphores != null) {
            for(long semaphore : renderFinishedSemaphores) {
                vkDestroySemaphore(device, semaphore, null);
            }
        }

        final int imageNum = getSwapChainImages().size();
        renderFinishedSemaphores = new ArrayList<>(imageNum);

        try(MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.create();
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            LongBuffer pSemaphore = stack.mallocLong(1);
            for(int i = 0; i < imageNum; ++i) {
                if(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create render-finished semaphore " + i);
                }
                renderFinishedSemaphores.add(pSemaphore.get(0));
            }
        }
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
        bindPipeline(boundPipeline);

        uploadAndBindUBOs(boundPipeline);

        drawIndexed(vertexBuffer, indexBuffer, indexCount);
    }

    private void drawAutoIndexed(ByteBuffer buffer, VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int drawMode, VertexFormat vertexFormat, int vertexCount) {
        vertexBuffer.copyToVertexBuffer(vertexFormat.getSize(), vertexCount, buffer);

        Pipeline boundPipeline = VRenderSystem.getShaderPipeline();
        bindPipeline(boundPipeline);

        uploadAndBindUBOs(boundPipeline);

        int indexCount;
        if(drawMode == 7) indexCount = vertexCount * 3 / 2;
        else if(drawMode == 6 || drawMode == 5) indexCount = (vertexCount - 2) * 3;
        else throw new RuntimeException("unknown drawMode: " + drawMode);

        drawIndexed(vertexBuffer, indexBuffer, indexCount);
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {

        pipeline.bindDescriptorSets(commandBuffers.get(currentFrame), currentFrame, uniformBuffers);
    }

    public void drawIndexed(VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int indexCount) {

        try(MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

            LongBuffer vertexBuffers = stack.longs(vertexBuffer.getId());
            LongBuffer offsets = stack.longs(vertexBuffer.getOffset());
//            Profiler.Push("bindVertex");
            vkCmdBindVertexBuffers(commandBuffer, 0, vertexBuffers, offsets);

            vkCmdBindIndexBuffer(commandBuffer, indexBuffer.getId(), indexBuffer.getOffset(), indexBuffer.indexType.vkType);
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
    public void drawIndexedIndirect(long vertexBuffer, IndexBuffer indexBuffer,
                                    long indirectBuffer, long indirectOffset, int drawCount) {
        if (drawCount <= 0) {
            return;
        }
        try(MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);

            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffer), stack.longs(0L));
            vkCmdBindIndexBuffer(commandBuffer, indexBuffer.getId(), indexBuffer.getOffset(), indexBuffer.indexType.vkType);

            // Without the multiDrawIndirect feature only drawCount == 1 is legal, so
            // replay the command array as one indirect call per command.
            if (Vulkan.multiDrawIndirectSupported) {
                vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffer, indirectOffset, drawCount, 20);
            } else {
                for (int i = 0; i < drawCount; i++) {
                    vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffer, indirectOffset + (long) i * 20, 1, 20);
                }
            }
        }
    }

    // Last pipeline bind recorded into the current frame's command buffer: consecutive
    // draws with an identical (pipeline, state) pair skip the vkCmdBindPipeline call.
    private static long lastBindKey = Long.MIN_VALUE;
    private static Pipeline lastBindPipeline;
    private static int lastBindFrame = -1;

    public void bindPipeline(Pipeline pipeline) {
        // The full state is packed into a long: no per-draw state objects are allocated
        // and the variant lookup is a single cached-key comparison in most draws.
        long stateKey = Pipeline.packStateKey(currentBlendState,
                VRenderSystem.depthTest, VRenderSystem.depthMask,
                Pipeline.DepthState.depthFuncToVulkan(VRenderSystem.depthFun),
                currentLogicOpState, VRenderSystem.getColorMask(),
                VRenderSystem.cull, VRenderSystem.topology);
        long handle = pipeline.getHandle(stateKey);

        // The command buffer is reset at each frame start, so a bind recorded in an
        // earlier use of this frame slot does not carry over.
        if (stateKey != lastBindKey || pipeline != lastBindPipeline || currentFrame != lastBindFrame) {
            vkCmdBindPipeline(commandBuffers.get(currentFrame), VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
            lastBindKey = stateKey;
            lastBindPipeline = pipeline;
            lastBindFrame = currentFrame;
        }

        usedPipelines.get(currentFrame).add(pipeline);
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
            VkClearValue colorValue = VkClearValue.calloc(stack);
            colorValue.color().float32(VRenderSystem.clearColor);

            VkClearValue depthValue = VkClearValue.calloc(stack);
            depthValue.depthStencil().depth(VRenderSystem.clearDepth);

            int attachmentsCount;
            VkClearAttachment.Buffer pAttachments;
            if (v == 0x100) {
                attachmentsCount = 1;

                pAttachments = VkClearAttachment.calloc(attachmentsCount, stack);

                VkClearAttachment clearDepth = pAttachments.get(0);
                clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                clearDepth.clearValue(depthValue);
            } else if (v == 0x4000) {
                attachmentsCount = 1;

                pAttachments = VkClearAttachment.calloc(attachmentsCount, stack);

                VkClearAttachment clearColor = pAttachments.get(0);
                clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                clearColor.colorAttachment(0);
                clearColor.clearValue(colorValue);
            } else if (v == 0x4100) {
                attachmentsCount = 2;

                pAttachments = VkClearAttachment.calloc(attachmentsCount, stack);

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
            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
            renderArea.extent().set(FrameTargets.currentWidth(), FrameTargets.currentHeight());

            VkClearRect.Buffer pRect = VkClearRect.calloc(1, stack);
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
