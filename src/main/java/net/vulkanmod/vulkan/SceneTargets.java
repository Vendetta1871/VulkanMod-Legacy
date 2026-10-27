package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

/**
 * Offscreen targets the world and GUI render into (B2), drawn with dynamic rendering and
 * blitted to the swapchain at the end of the frame. One set is shared by all frames in
 * flight; the queue serializes frames and the barriers in {@link #beginRendering} order
 * the reuse.
 */
public final class SceneTargets {

    /**
     * LDR for now: a float target would skip the [0,1] clamp vanilla blending relies on.
     * Moves to RGBA16F with a tonemapping composite once HDR lighting exists.
     */
    public static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    private static int depthFormat;
    private static boolean depthHasStencil;

    private static int width, height;
    private static long colorImage, colorMemory, colorView;
    private static long depthImage, depthMemory, depthView;

    private SceneTargets() {}

    static void init(int depthFormat) {
        SceneTargets.depthFormat = depthFormat;
        depthHasStencil = depthFormat == VK_FORMAT_D32_SFLOAT_S8_UINT || depthFormat == VK_FORMAT_D24_UNORM_S8_UINT;
    }

    public static int depthFormat() { return depthFormat; }
    public static int stencilFormat() { return depthHasStencil ? depthFormat : VK_FORMAT_UNDEFINED; }
    public static int width() { return width; }
    public static int height() { return height; }
    public static long colorImage() { return colorImage; }
    public static long colorView() { return colorView; }
    public static long depthImage() { return depthImage; }
    public static long depthView() { return depthView; }

    private static int depthAspect() {
        return depthHasStencil ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
    }

    /** (Re)creates the targets; the caller guarantees the GPU no longer uses the old ones. */
    static void create(int w, int h) {
        destroy();
        width = w;
        height = h;

        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);

            MemoryManager.createImage(w, h, 1, COLOR_FORMAT, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            colorImage = pImage.get(0);
            colorMemory = pMemory.get(0);
            colorView = Vulkan.createImageView(colorImage, COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT, 1);

            // STORE + SAMPLED: FSR, motion-vector reprojection and RT read the depth later.
            MemoryManager.createImage(w, h, 1, depthFormat, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            depthImage = pImage.get(0);
            depthMemory = pMemory.get(0);
            // Sampled views see depth only; the attachment view is the same (stencil unused).
            depthView = Vulkan.createImageView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT, 1);
        }
    }

    static void destroy() {
        VkDevice device = Vulkan.getDevice();
        // Views go before the images they were created from (N24).
        if (colorView != 0L) vkDestroyImageView(device, colorView, null);
        if (colorImage != 0L) MemoryManager.freeImage(colorImage, colorMemory);
        if (depthView != 0L) vkDestroyImageView(device, depthView, null);
        if (depthImage != 0L) MemoryManager.freeImage(depthImage, depthMemory);
        colorView = colorImage = colorMemory = 0L;
        depthView = depthImage = depthMemory = 0L;
    }

    /**
     * Transitions both targets for attachment use and begins dynamic rendering with a clear.
     * Contents of the previous frame are discarded (oldLayout UNDEFINED); the source scopes
     * cover the previous frame's blit read and depth writes on the same queue.
     */
    static void beginRendering(VkCommandBuffer cmd, float[] clearColor, MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(barriers.get(0), colorImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        imageBarrier(barriers.get(1), depthImage, depthAspect(),
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        pipelineBarrier(cmd, barriers, stack);

        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
        color.get(0).sType$Default()
                .imageView(colorView)
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        color.get(0).clearValue().color().float32(0, clearColor[0]).float32(1, clearColor[1])
                .float32(2, clearColor[2]).float32(3, clearColor[3]);

        VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(depthView)
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        depth.clearValue().depthStencil().set(1.0f, 0);

        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1)
                .pColorAttachments(color)
                .pDepthAttachment(depth);
        if (depthHasStencil) info.pStencilAttachment(depth);
        info.renderArea().offset().set(0, 0);
        info.renderArea().extent().set(width, height);

        vkCmdBeginRendering(cmd, info);
    }

    /**
     * Ends rendering and copies the color target into the acquired swapchain image, leaving
     * it in PRESENT_SRC. The submit waits for the acquire at the TRANSFER stage, which the
     * swapchain barrier's source scope chains onto.
     */
    static void endAndPresentCopy(VkCommandBuffer cmd, long swapchainImage, int swapchainFormat,
                                  int swapW, int swapH, MemoryStack stack) {
        vkCmdEndRendering(cmd);

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(pre.get(0), colorImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        imageBarrier(pre.get(1), swapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        pipelineBarrier(cmd, pre, stack);

        if (swapchainFormat == COLOR_FORMAT && swapW == width && swapH == height) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.extent().set(width, height, 1);
            vkCmdCopyImage(cmd, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    swapchainImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        } else {
            // Blit converts RGBA <-> BGRA and scales when the extents differ.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.srcOffsets(1).set(width, height, 1);
            region.dstOffsets(1).set(swapW, swapH, 1);
            vkCmdBlitImage(cmd, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    swapchainImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_LINEAR);
        }

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        imageBarrier(post.get(0), swapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_NONE, 0,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        pipelineBarrier(cmd, post, stack);
    }

    static void imageBarrier(VkImageMemoryBarrier2 b, long image, int aspect,
                             long srcStage, long srcAccess, long dstStage, long dstAccess,
                             int oldLayout, int newLayout) {
        b.sType$Default()
                .srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        b.subresourceRange().set(aspect, 0, 1, 0, 1);
    }

    static void pipelineBarrier(VkCommandBuffer cmd, VkImageMemoryBarrier2.Buffer barriers, MemoryStack stack) {
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
    }
}
