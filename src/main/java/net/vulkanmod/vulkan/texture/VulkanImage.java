package net.vulkanmod.vulkan.texture;

import net.vulkanmod.vulkan.*;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;

import static net.vulkanmod.vulkan.Vulkan.*;
import static net.vulkanmod.vulkan.memory.MemoryManager.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanImage {
    private static VkDevice device = Vulkan.getDevice();

    private long id;
    private long textureImageMemory;
    private long allocation;
    private long textureImageView;

    private long textureSampler;

    /**
     * Samplers carry no per-image state once the LOD clamp depends only on the mipmap
     * flag (maxLod 0 without mips; LOD_CLAMP_NONE with them, where the hardware clamps
     * the LOD to the image's actual level count), so they are shared process-wide by
     * (blur, clamp, mipmap). Key includes clamp: two images differing only in addressing
     * must not share a sampler.
     */
    /** Key: filter/address/mip bits in the low byte, the LOD bias float bits above. */
    private static final HashMap<Long, Long> SHARED_SAMPLERS = new HashMap<>();
    private boolean samplerBlur, samplerClamp, samplerMipmap;

    private int mipLevels;
    private int width;
    private int height;
    private int formatSize;

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    private int currentLayout;
    /** Command buffer the barriers/copies of the current upload are recorded into. */
    private VkCommandBuffer transferCmd;
    /** Pool command buffer of a synchronous upload; null for frame-window/async ones. */
    private TransferQueue.CommandBuffer queuedCmd;

    public VulkanImage(int width, int height, int formatSize, boolean blur, boolean clamp, ByteBuffer buffer) {
        this(1, width, height, formatSize, blur, clamp);
        uploadTexture(width, height, formatSize, buffer);
    }

    public VulkanImage(int mipLevels, int width, int height, int formatSize, boolean blur, boolean clamp) {
        this.mipLevels = mipLevels;
        this.width = width;
        this.height = height;
        this.formatSize = formatSize;

        createTextureImage(mipLevels, width, height);
        createTextureImageView(mipLevels);
        updateTextureSampler(blur, clamp, mipLevels > 1);
    }

    public VulkanImage(int miplevel, int width, int height) {
        this(miplevel, width, height, 4, false, true);
    }

    public VulkanImage(int width, int height) {
        this(1, width, height, 4, false, false);
    }

    public static VulkanImage createWhiteTexture() {
        int i = 0xFFFFFFFF;
        ByteBuffer buffer = MemoryUtil.memAlloc(4);
        buffer.putInt(0, i);

        VulkanImage image = new VulkanImage(1, 1, 4, false, false, buffer);
        MemoryUtil.memFree(buffer);
        return image;
    }

    private void createTextureImage(int miplevel, int width, int height) {

        try(MemoryStack stack = stackPush()) {

            LongBuffer pTextureImage = stack.mallocLong(1);
            //LongBuffer pAllocation = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.pointers(0L);

            MemoryManager.createImage(width, height, miplevel,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TILING_OPTIMAL,
                    // TRANSFER_SRC: the mirror readback (copyToBuffer) copies these
                    // images out; validation rejects the copy without the usage bit.
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    pTextureImage,
                    pAllocation);

            id = pTextureImage.get(0);
            allocation = pAllocation.get(0);



        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void uploadTexture(int width, int height, int formatSize, ByteBuffer buffer) {
        // N26: inside the frame window the full upload rides the frame's transfer
        // command buffer, exactly like uploadSubTextureAsync. A separate TransferQueue
        // submit would execute immediately - before the copies already recorded into
        // the frame buffer for the same image (those run at frame start) - reversing
        // the issue order of the two paths. Riding one command buffer keeps them in
        // recording order. Outside the frame window the synchronous path below is
        // fenced on its own and nothing can be pending.
        if (Drawer.getFrameTransferCmd() != null) {
            uploadSubTextureAsync(0, width, height, 0, 0, formatSize, 0, 0, width, buffer);
            return;
        }

        try(MemoryStack stack = stackPush()) {
            long imageSize = width * height * formatSize;

            queuedCmd = TransferQueue.beginCommands();
            transferCmd = queuedCmd.getHandle();
            transferDstLayout();

            StagingBuffer stagingBuffer = Vulkan.getStagingBuffer(Drawer.getCurrentFrame());

            stagingBuffer.copyBuffer((int)imageSize, buffer);

            copyBufferToImage(stagingBuffer.getBufferId(), id, 0, width, height, 0, 0, (int)stagingBuffer.getOffset(), 0, 0);
        }

    }

    /**
     * Copies a sub-region (or the whole image) into this texture.
     *
     * <p>Frame-start semantics (N26): inside the frame window the copy rides the
     * frame's transfer command buffer, which is submitted ahead of the frame's draw
     * commands in the same submit. Every copy recorded during the frame therefore
     * executes before every draw of the frame - a texture updated mid-frame shows
     * the update in the whole frame, including draws recorded before the update
     * call (GL would keep the old texture for those). The current callers (atlas
     * mirror, lightmap, DynamicTexture) update once per frame ahead of the draws
     * that read them, so the difference is not observable; nothing may rely on a
     * mid-frame update affecting only later draws.</p>
     *
     * <p>Both upload paths ride the same frame command buffer inside the window, so
     * copies of one image always execute in issue order.</p>
     */
    public void uploadSubTextureAsync(int mipLevel, int width, int height, int xOffset, int yOffset, int formatSize, int unpackSkipRows, int unpackSkipPixels, int unpackRowLength, ByteBuffer buffer) {
        long imageSize = buffer.limit();

        // Inside the frame window the copy rides the frame's transfer command buffer
        // (one submit per frame, fenced by the frame fence); outside it the copy gets
        // its own TransferQueue command buffer and fence.
        TransferQueue.CommandBuffer queued = null;

        transferCmd = Drawer.getFrameTransferCmd();
        if (transferCmd == null) {
            queued = TransferQueue.getCommandBuffer();
            transferCmd = queued.getHandle();
        }

        // Within a tile batch the whole group of copies shares the one layout round
        // trip recorded by beginTileBatch/endTileBatch (N11); per-copy transitions
        // meant eight full-image barriers per animated sprite per tick.
        if (tileBatchDepth == 0) {
            transferDstLayout();
        }

        StagingBuffer stagingBuffer = Vulkan.getStagingBuffer(Drawer.getCurrentFrame());

        stagingBuffer.copyBuffer((int)imageSize, buffer);

        copyBufferToImageAsync(stagingBuffer.getBufferId(), id, mipLevel, width, height, xOffset, yOffset,
                (int) (stagingBuffer.getOffset() + (unpackRowLength * unpackSkipRows + unpackSkipPixels) * 4), unpackRowLength, height);

        // Leave the image directly in SHADER_READ_ONLY inside the same command buffer,
        // so the descriptor-time readOnlyLayout() is a no-op and no extra submit is spent.
        if (tileBatchDepth == 0) {
            finishTransferLayout();
        }

        if (queued != null) {
            TransferQueue.endIfNeeded(queued);
        }
    }

    /**
     * Depth of the tile-upload batch (N11): while nonzero, uploadSubTextureAsync skips
     * its layout round trip and the group shares the transitions recorded here.
     */
    private int tileBatchDepth;

    /**
     * Opens a group of sub-image copies that share one SHADER_READ -> TRANSFER_DST ->
     * SHADER_READ round trip instead of paying one pair per mip copy (N11). The caller
     * must already have opened the command buffer the copies will record into (see
     * TransferQueue.startRecording); the WAR hazard against frames still sampling the
     * image is covered by the single opening barrier.
     */
    public void beginTileBatch() {
        if (tileBatchDepth++ != 0) {
            return;
        }
        transferCmd = Drawer.getFrameTransferCmd();
        if (transferCmd == null) {
            transferCmd = TransferQueue.getCommandBuffer().getHandle();
        }
        transferDstLayout();
    }

    /** Closes the group; one barrier returns the image to SHADER_READ_ONLY. */
    public void endTileBatch() {
        if (--tileBatchDepth != 0) {
            return;
        }
        finishTransferLayout();
    }

    /** Debug helper: copies the image contents to the CPU and writes them out as a PNG. */
    public void dumpToPng(java.io.File file) {
        dumpToPng(file, 0);
    }

    /** Debug helper: dumps a single mip level. */
    public void dumpToPng(java.io.File file, int level) {
        try(MemoryStack stack = stackPush()) {
            int mipW = Math.max(1, width >> level);
            int mipH = Math.max(1, height >> level);
            int size = mipW * mipH * 4;

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.pointers(0L);
            createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                    pBuffer, pAllocation);

            TransferQueue.CommandBuffer transferCommand = TransferQueue.beginCommands();
            VkCommandBuffer commandBuffer = transferCommand.getHandle();

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.create(1);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_SHADER_READ_BIT);
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            barrier.oldLayout(currentLayout);
            barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(id);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseMipLevel(level);
            barrier.subresourceRange().levelCount(1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.create(1);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            region.imageSubresource().mipLevel(level);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent(VkExtent3D.create().set(mipW, mipH, 1));

            vkCmdCopyImageToBuffer(commandBuffer, id, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pBuffer.get(0), region);

            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);
            currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

            TransferQueue.endCommands(transferCommand);
            vkWaitForFences(device, transferCommand.getFence(), true, VUtil.UINT64_MAX);

            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(mipW, mipH,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            MapAndCopy(pAllocation.get(0), size, (data) -> {
                ByteBuffer pixels = data.getByteBuffer(0, size);
                for(int y = 0; y < mipH; ++y) {
                    for(int x = 0; x < mipW; ++x) {
                        int i = (y * mipW + x) * 4;
                        int rgb = (pixels.get(i) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | (pixels.get(i + 2) & 0xFF);
                        image.setRGB(x, y, rgb);
                    }
                }
            });

            javax.imageio.ImageIO.write(image, "png", file);
            freeBuffer(pBuffer.get(0), pAllocation.get(0));
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write texture dump " + file, e);
        }
    }

    public static void downloadTexture(int width, int height, int formatSize, ByteBuffer buffer, long image) {
        try(MemoryStack stack = stackPush()) {
            long imageSize = width * height * formatSize;

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            PointerBuffer pStagingAllocation = stack.pointers(0L);
            createBuffer(imageSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                    pStagingBuffer,
                    pStagingAllocation);

            copyImageToBuffer(pStagingBuffer.get(0), image, 0, width, height, 0, 0, 0, 0, 0);

            MapAndCopy(pStagingAllocation.get(0), imageSize,
                    (data) -> VUtil.memcpy(buffer, data.getByteBuffer(0, (int)imageSize))
            );

            MemoryManager.addToFreeable(pStagingBuffer.get(0), pStagingAllocation.get(0));
        }

    }

    private void transferDstLayout() {
        if (this.currentLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) return;

        try(MemoryStack stack = stackPush()) {

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.create(1);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            // Keep the existing contents: UNDEFINED is only legal before the first
            // upload, it would let the driver discard every mip level of the atlas
            // (and on AMD with DCC it actually does).
            barrier.oldLayout(this.currentLayout);
            barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(this.id);

            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(mipLevels);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);

            int sourceStage;
            int destinationStage;

            if (this.currentLayout == VK_IMAGE_LAYOUT_UNDEFINED) {
                barrier.srcAccessMask(0);
                sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            } else {
                // WAR hazard: the copy must not overwrite texels that frames still in
                // flight are sampling (the lightmap and the atlas tiles are re-uploaded
                // while earlier frames are still rendering).
                barrier.srcAccessMask(VK_ACCESS_SHADER_READ_BIT);
                sourceStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;
            }
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;

            vkCmdPipelineBarrier(transferCmd,
                    sourceStage, destinationStage,
                    0,
                    null,
                    null,
                    barrier);

        }

        currentLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    }

    /**
     * TRANSFER_DST -&gt; SHADER_READ_ONLY barrier recorded into the transfer command buffer
     * the last copy went to, so the transition needs no extra submit and is ordered
     * against the copy by construction.
     */
    private void finishTransferLayout() {
        if (this.currentLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) return;

        try(MemoryStack stack = stackPush()) {

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.create(1);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.oldLayout(this.currentLayout);
            barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(this.id);

            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(mipLevels);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);

            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            // The terrain/entity shaders sample these textures from both stages.
            vkCmdPipelineBarrier(transferCmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    0,
                    null,
                    null,
                    barrier);
        }

        this.currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    /**
     * Fallback transition for images left in TRANSFER_DST by the synchronous upload
     * path; the async path already ends every copy in SHADER_READ_ONLY (see
     * {@link #finishTransferLayout()}), so this is normally a no-op.
     */
    public void readOnlyLayout() {
        if (this.currentLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) return;
        // Nothing was ever uploaded: there is no content to make readable yet, and the
        // next transferDstLayout() starts from UNDEFINED anyway.
        if (this.currentLayout == VK_IMAGE_LAYOUT_UNDEFINED) return;

        TransferQueue.CommandBuffer commandBuffer = TransferQueue.beginCommands();

        try(MemoryStack stack = stackPush()) {

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.create(1);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.oldLayout(this.currentLayout);
            barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(this.id);

            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(mipLevels);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);

            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(commandBuffer.getHandle(),
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    0,
                    null,
                    null,
                    barrier);
        }

        TransferQueue.endCommands(commandBuffer);

        this.currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    private void createTextureImageView(int mipLevels) {
        textureImageView = createImageView(id, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, mipLevels);
        LIVE_VIEWS.add(textureImageView);
    }

    /**
     * Destroys the process-wide shared samplers (called from Vulkan.cleanUp at teardown).
     */
    public static void destroySharedSamplers() {
        for(long sampler : SHARED_SAMPLERS.values()) {
            vkDestroySampler(device, sampler, null);
        }
        SHARED_SAMPLERS.clear();
    }

    /** Texture views still alive; destroyed at shutdown before their images (M17). */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet LIVE_VIEWS =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /**
     * Destroys every texture image view not already freed through {@link #free()}.
     * Must run before {@link net.vulkanmod.vulkan.memory.MemoryManager#destroyAllResources()}
     * tears down the images they reference.
     */
    public static void destroyAllViews() {
        for(long view : LIVE_VIEWS) {
            vkDestroyImageView(device, view, null);
        }
        LIVE_VIEWS.clear();
    }

    private static long getOrCreateSampler(boolean blur, boolean clamp, boolean mipmap, float lodBias) {
        long key = ((blur ? 1 : 0) | (clamp ? 2 : 0) | (mipmap ? 4 : 0))
                | ((long) Float.floatToIntBits(lodBias) << 8);
        Long sampler = SHARED_SAMPLERS.get(key);
        if (sampler != null) return sampler;

        long created = createSampler(blur, clamp, mipmap, lodBias);
        SHARED_SAMPLERS.put(key, created);
        return created;
    }

    private static long createSampler(boolean blur, boolean clamp, boolean mipmap, float lodBias) {

        try(MemoryStack stack = stackPush()) {

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.create();
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);

            if(blur) {
                samplerInfo.magFilter(VK_FILTER_LINEAR);
                samplerInfo.minFilter(VK_FILTER_LINEAR);
            } else {
                samplerInfo.magFilter(VK_FILTER_NEAREST);
                // NEAREST_MIPMAP_LINEAR: nearest texel within a level, linear between
                // levels - the filtering vanilla applies to the atlas. A LINEAR minifier
                // would blend across sprite borders into the transparent atlas padding,
                // drawing a grid of dark seams on distant blocks.
                samplerInfo.minFilter(VK_FILTER_NEAREST);
            }


            if(clamp) {
                samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            } else {
                samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT);
                samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT);
                samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            }

            samplerInfo.anisotropyEnable(false);
            //samplerInfo.maxAnisotropy(16.0f);
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_WHITE);
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(false);
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS);

            samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);
            // Without mips pin level 0; with them let the hardware clamp the LOD to the
            // sampled image's own level count, so one sampler fits every mipmapped image.
            samplerInfo.maxLod(mipmap ? VK_LOD_CLAMP_NONE : 0.0F);
            samplerInfo.minLod(0.0F);
            // F6: negative bias for world textures rendered below display resolution.
            samplerInfo.mipLodBias(lodBias);

            LongBuffer pTextureSampler = stack.mallocLong(1);

            if(vkCreateSampler(getDevice(), samplerInfo, null, pTextureSampler) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create texture sampler");
            }

            return pTextureSampler.get(0);
        }
    }

    public void updateTextureSampler(boolean blur, boolean clamp, boolean mipmap) {
        this.samplerBlur = blur;
        this.samplerClamp = clamp;
        this.samplerMipmap = mipmap;
        this.textureSampler = getOrCreateSampler(blur, clamp, mipmap, 0.0f);
    }

    private void copyBufferToImage(long buffer, long image, int mipLevel, int width, int height) {
        copyBufferToImage(buffer, image, mipLevel, width, height, 0, 0);
    }

    private void copyBufferToImage(long buffer, long image, int mipLevel, int width, int height, int xOffset, int yOffset){
        copyBufferToImage(buffer, image, mipLevel, width, height, xOffset, yOffset, 0, 0, 0);
    }

    private void copyBufferToImage(long buffer, long image, int mipLevel, int width, int height, int xOffset, int yOffset, int bufferOffset, int bufferRowLenght, int bufferImageHeight) {

        try(MemoryStack stack = stackPush()) {

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.create(1);
            region.bufferOffset(bufferOffset);
            region.bufferRowLength(bufferRowLenght);   // Tightly packed
            region.bufferImageHeight(bufferImageHeight);  // Tightly packed
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            region.imageSubresource().mipLevel(mipLevel);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(xOffset, yOffset, 0);
            region.imageExtent(VkExtent3D.create().set(width, height, 1));

            vkCmdCopyBufferToImage(transferCmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            TransferQueue.endCommands(queuedCmd);
            vkWaitForFences(device, queuedCmd.getFence(), true, VUtil.UINT64_MAX);
            queuedCmd = null;

        }
    }

    private void copyBufferToImageAsync(long buffer, long image, int mipLevel, int width, int height, int xOffset, int yOffset, int bufferOffset, int bufferRowLenght, int bufferImageHeight) {

        try(MemoryStack stack = stackPush()) {

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.create(1);
            region.bufferOffset(bufferOffset);
            region.bufferRowLength(bufferRowLenght);   // Tightly packed
            region.bufferImageHeight(bufferImageHeight);  // Tightly packed
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            region.imageSubresource().mipLevel(mipLevel);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(xOffset, yOffset, 0);
            region.imageExtent(VkExtent3D.create().set(width, height, 1));

            vkCmdCopyBufferToImage(transferCmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }

    private static void copyImageToBuffer(long buffer, long image, int mipLevel, int width, int height, int xOffset, int yOffset, int bufferOffset, int bufferRowLenght, int bufferImageHeight) {

        try(MemoryStack stack = stackPush()) {

            TransferQueue.CommandBuffer commandBuffer = TransferQueue.beginCommands();

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.create(1);
            region.bufferOffset(bufferOffset);
            region.bufferRowLength(bufferRowLenght);   // Tightly packed
            region.bufferImageHeight(bufferImageHeight);  // Tightly packed
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            region.imageSubresource().mipLevel(mipLevel);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(xOffset, yOffset, 0);
            region.imageExtent(VkExtent3D.create().set(width, height, 1));

            vkCmdCopyImageToBuffer(commandBuffer.getHandle(), image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);

            TransferQueue.endCommands(commandBuffer);
            vkWaitForFences(device, commandBuffer.getFence(), true, VUtil.UINT64_MAX);
        }
    }

    public static void transitionImageLayout(VkCommandBuffer commandBuffer, long image, int format, int oldLayout, int newLayout, int mipLevels) {

        try(MemoryStack stack = stackPush()) {

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.create(1);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.oldLayout(oldLayout);
            barrier.newLayout(newLayout);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(image);

            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(mipLevels);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            if(newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {

                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);

                if(hasStencilComponent(format)) {
                    barrier.subresourceRange().aspectMask(
                            barrier.subresourceRange().aspectMask() | VK_IMAGE_ASPECT_STENCIL_BIT);
                }

            } else {
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            }

            int sourceStage;
            int destinationStage;

            if(oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {

                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);

                sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;

            } else if(oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {

                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

            } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {

                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

                sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                destinationStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;

            } else {
                throw new IllegalArgumentException("Unsupported layout transition");
            }

            vkCmdPipelineBarrier(commandBuffer,
                    sourceStage, destinationStage,
                    0,
                    null,
                    null,
                    barrier);

        }
    }

    private static boolean hasStencilComponent(int format) {
        return format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT;
    }

    /**
     * Frees the image and its view once the frames in flight that may still sample it
     * have finished (same Frames+1 delay as the buffers in MemoryManager). The samplers
     * are shared process-wide and stay alive.
     */
    public void free() {
        final long imageId = this.id;
        final long imageAllocation = this.allocation;
        final long view = this.textureImageView;

        this.id = 0;
        this.allocation = 0;
        this.textureImageView = 0;
        this.currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        // No selector slot may keep pointing at the freed image (N15).
        VTextureSelector.onImageFreed(this);

        if (imageId != 0) {
            LIVE_VIEWS.remove(view);
            MemoryManager.addToFreeable(() -> {
                vkDestroyImageView(device, view, null);
                MemoryManager.freeImage(imageId, imageAllocation);
            });
        }
    }

    public long getId() { return id;}

    /** True once {@link #free()} has run; the view is destroyed or scheduled for it (N15). */
    public boolean isFreed() { return id == 0; }
    public long getTextureImageView() { return textureImageView; }
    public long getTextureSampler() { return textureSampler; }

    /**
     * The sampler with a mip LOD bias (F6). Only mipmapped textures get a biased variant;
     * the others ignore the bias.
     */
    public long getTextureSampler(float lodBias) {
        if (lodBias == 0.0f || !samplerMipmap) return textureSampler;
        return getOrCreateSampler(samplerBlur, samplerClamp, samplerMipmap, lodBias);
    }
}
