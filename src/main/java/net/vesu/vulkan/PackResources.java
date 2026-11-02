package net.vesu.vulkan;

import net.vesu.Initializer;
import net.vesu.pack.host.Resources;
import net.vesu.pack.source.PackSource;
import net.vesu.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * The images and storage buffers of a pack (pack-format.md, "Resources"): one descriptor set,
 * shared by every program and frame. The images stay in GENERAL layout; {@link #barrier} orders
 * the stages that use them.
 */
final class PackResources {

    private static final int STAGES = VK_SHADER_STAGE_ALL_GRAPHICS | VK_SHADER_STAGE_COMPUTE_BIT;
    private static final long SHADER_STAGES = VK_PIPELINE_STAGE_2_PRE_RASTERIZATION_SHADERS_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
            | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

    private static final class Gpu {
        long image, memory, view;
        /** The content of a raw texture, until it is uploaded. */
        ByteBuffer pixels;
        int width, height, depth;
        /** Layout undefined, content not cleared. */
        boolean fresh = true;
    }

    private final Resources resources;
    private final Gpu[] images;
    private final long[] buffers, bufferMemory;
    private final long pool, set, emptyLayout, setLayout, linearSampler, nearestSampler;
    private boolean buffersFresh = true;
    /** The source is closed by the time the images exist. */
    private final byte[][] rawFiles;
    private final List<long[]> staging = new ArrayList<>();

    /** Null for a pack without resources, which costs nothing. */
    static PackResources of(Resources resources, PackSource src) throws IOException {
        return resources.isEmpty() ? null : new PackResources(resources, src);
    }

    private PackResources(Resources resources, PackSource src) throws IOException {
        this.resources = resources;
        rawFiles = new byte[resources.images.size()][];
        for (int i = 0; i < rawFiles.length; i++) {
            Resources.Raw raw = resources.images.get(i).raw();
            if (raw != null) rawFiles[i] = src.readBytes(raw.file());
        }
        VkDevice device = Vulkan.getDevice();
        int imageCount = resources.images.size(), bufferCount = resources.buffers.size();
        images = new Gpu[imageCount];
        buffers = new long[bufferCount];
        bufferMemory = new long[bufferCount];
        try (MemoryStack stack = stackPush()) {
            int samplers = 0;
            int storage = 0;
            for (Resources.Image m : resources.images) {
                if (m.sampler() != null) samplers++;
                if (m.raw() == null) storage++;
            }
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(storage + samplers + bufferCount, stack);
            int n = 0;
            for (int i = 0; i < imageCount; i++) {
                if (resources.images.get(i).raw() == null) {
                    b.get(n++).binding(Resources.storageBinding(i)).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .descriptorCount(1).stageFlags(STAGES);
                }
                if (resources.images.get(i).sampler() != null) {
                    b.get(n++).binding(Resources.samplerBinding(i)).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(1).stageFlags(STAGES);
                }
            }
            for (Resources.Buffer r : resources.buffers) {
                b.get(n++).binding(Resources.bufferBinding(r.binding())).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(STAGES);
            }
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, p), "pack resource set layout");
            setLayout = p.get(0);
            ComputeSets.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default(), null, p), "empty set layout");
            emptyLayout = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.max(1, storage));
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(Math.max(1, samplers));
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(Math.max(1, bufferCount));
            ComputeSets.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(sizes), null, p), "pack resource pool");
            pool = p.get(0);
            ComputeSets.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), p), "pack resource set");
            set = p.get(0);

            linearSampler = sampler(VK_FILTER_LINEAR, stack);
            nearestSampler = sampler(VK_FILTER_NEAREST, stack);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            for (int i = 0; i < bufferCount; i++) {
                MemoryManager.createBuffer(resources.buffers.get(i).size(),
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pBuffer, pMemory);
                buffers[i] = pBuffer.get(0);
                bufferMemory[i] = pMemory.get(0);
            }
            if (bufferCount > 0) {
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(bufferCount, stack);
                for (int i = 0; i < bufferCount; i++) {
                    VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                    info.get(0).buffer(buffers[i]).offset(0).range(VK_WHOLE_SIZE);
                    w.get(i).sType$Default().dstSet(set).dstBinding(Resources.bufferBinding(resources.buffers.get(i).binding()))
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
                }
                vkUpdateDescriptorSets(device, w, null);
            }
        }
    }

    private static long sampler(int filter, MemoryStack stack) {
        LongBuffer p = stack.mallocLong(1);
        ComputeSets.check(vkCreateSampler(Vulkan.getDevice(), VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(filter).minFilter(filter).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, p), "pack resource sampler");
        return p.get(0);
    }

    long setLayout() {
        return setLayout;
    }

    /** Fills set 1 of a program that has no vanilla set. */
    long emptyLayout() {
        return emptyLayout;
    }

    long set() {
        return set;
    }

    /** The buffer of {@code bufferObject.<binding>}. */
    long buffer(int binding) {
        for (int i = 0; i < buffers.length; i++) {
            if (resources.buffers.get(i).binding() == binding) return buffers[i];
        }
        throw new IllegalArgumentException("no buffer at binding " + binding);
    }

    /** Creates the images, and again those that follow the render size when it changed; the device is idle. */
    void ensure(int sceneWidth, int sceneHeight) {
        for (int i = 0; i < images.length; i++) {
            Resources.Image m = resources.images.get(i);
            int w = m.scaleX() > 0 ? Math.max(1, Math.round(sceneWidth * m.scaleX())) : m.width();
            int h = m.scaleX() > 0 ? Math.max(1, Math.round(sceneHeight * m.scaleY())) : Math.max(1, m.height());
            int d = Math.max(1, m.depth());
            Gpu old = images[i];
            if (old != null && old.width == w && old.height == h && old.depth == d) continue;
            if (old != null) destroy(old);
            images[i] = create(i, m, w, h, d);
        }
    }

    private Gpu create(int index, Resources.Image m, int w, int h, int d) {
        Gpu g = new Gpu();
        g.width = w;
        g.height = h;
        g.depth = d;
        int format = PackRuntime.vkFormat(m.format());
        int dimensions = m.dimensions();
        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            MemoryManager.createImage(dimensions == 3 ? VK_IMAGE_TYPE_3D : dimensions == 1 ? VK_IMAGE_TYPE_1D : VK_IMAGE_TYPE_2D,
                    w, h, d, 1, format, VK_IMAGE_TILING_OPTIMAL,
                    (m.raw() == null ? VK_IMAGE_USAGE_STORAGE_BIT : 0) | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            g.image = pImage.get(0);
            g.memory = pMemory.get(0);

            VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(g.image).format(format)
                    .viewType(dimensions == 3 ? VK_IMAGE_VIEW_TYPE_3D : dimensions == 1 ? VK_IMAGE_VIEW_TYPE_1D : VK_IMAGE_VIEW_TYPE_2D);
            view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateImageView(Vulkan.getDevice(), view, null, p), "pack resource view");
            g.view = p.get(0);

            int writes = (m.sampler() != null ? 1 : 0) + (m.raw() == null ? 1 : 0), written = 0;
            VkWriteDescriptorSet.Buffer w2 = VkWriteDescriptorSet.calloc(writes, stack);
            if (m.raw() == null) {
                VkDescriptorImageInfo.Buffer storage = VkDescriptorImageInfo.calloc(1, stack);
                storage.get(0).imageView(g.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                w2.get(written++).sType$Default().dstSet(set).dstBinding(Resources.storageBinding(index))
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(storage);
            }
            if (m.sampler() != null) {
                VkDescriptorImageInfo.Buffer sampled = VkDescriptorImageInfo.calloc(1, stack);
                sampled.get(0).imageView(g.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .sampler(m.format().isInteger() ? nearestSampler : linearSampler);
                w2.get(written).sType$Default().dstSet(set).dstBinding(Resources.samplerBinding(index))
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(sampled);
            }
            vkUpdateDescriptorSets(Vulkan.getDevice(), w2, null);
        }
        if (m.raw() != null) g.pixels = pixels(m, rawFiles[index], w, h, d);
        return g;
    }

    /** Tightly packed texels of the image's format from the pack's file, null when the file does not fit. */
    private static ByteBuffer pixels(Resources.Image m, byte[] file, int w, int h, int d) {
        Resources.Raw raw = m.raw();
        int pixel = raw.components() * raw.componentBytes();
        long expected = (long) w * h * d * pixel;
        if (file.length != expected) {
            Initializer.LOGGER.error("[shaderpacks] raw texture {}: {} bytes, not {}", m.name(), file.length, expected);
            return null;
        }
        int bytes = raw.componentBytes();
        boolean addAlpha = raw.components() == 3;
        int outPixel = addAlpha ? 4 * bytes : pixel;
        ByteBuffer out = MemoryUtil.memAlloc(w * h * d * outPixel).order(ByteOrder.nativeOrder());
        for (int p = 0; p < w * h * d; p++) {
            int at = p * pixel;
            for (int c = 0; c < raw.components(); c++) {
                // BGR order swaps the first and third components.
                int from = raw.bgra() && c != 1 && c != 3 ? 2 - c : c;
                for (int k = 0; k < bytes; k++) out.put(file[at + from * bytes + k]);
            }
            if (addAlpha) {
                switch (raw.pixelType()) {
                    case "FLOAT" -> out.putFloat(1.0f);
                    case "HALF_FLOAT" -> out.putShort((short) 0x3C00);
                    default -> {
                        for (int k = 0; k < bytes; k++) out.put((byte) 0xFF);
                    }
                }
            }
        }
        return out.flip();
    }

    private void upload(VkCommandBuffer cmd, Gpu g, MemoryStack stack) {
        LongBuffer pBuffer = stack.mallocLong(1);
        PointerBuffer pMemory = stack.mallocPointer(1);
        MemoryManager.createBufferHostAccess(g.pixels.remaining(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, pBuffer, pMemory);
        staging.add(new long[]{pBuffer.get(0), pMemory.get(0)});
        MemoryUtil.memCopy(MemoryUtil.memAddress(g.pixels), MemoryManager.Map(pMemory.get(0)).get(0), g.pixels.remaining());
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.get(0).imageExtent().set(g.width, g.height, g.depth);
        vkCmdCopyBufferToImage(cmd, pBuffer.get(0), g.image, VK_IMAGE_LAYOUT_GENERAL, region);
        MemoryUtil.memFree(g.pixels);
        g.pixels = null;
    }

    /** Start of the frame, ahead of every use: layouts of new images, clears, zeroed new buffers. */
    void begin(VkCommandBuffer cmd, MemoryStack stack) {
        int fresh = 0;
        for (Gpu g : images) if (g.fresh) fresh++;
        if (fresh > 0) {
            VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(fresh, stack);
            int n = 0;
            for (Gpu g : images) {
                if (!g.fresh) continue;
                FrameTargets.imageBarrier(b.get(n++), g.image, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            }
            FrameTargets.pipelineBarrier(cmd, b, stack);
        }
        VkClearColorValue zero = VkClearColorValue.calloc(stack);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        for (int i = 0; i < images.length; i++) {
            Gpu g = images[i];
            if (g.pixels != null) {
                upload(cmd, g, stack);
            } else if (g.fresh || resources.images.get(i).clear()) {
                vkCmdClearColorImage(cmd, g.image, VK_IMAGE_LAYOUT_GENERAL, zero, range);
            }
            g.fresh = false;
        }
        if (buffersFresh) {
            buffersFresh = false;
            for (long buffer : buffers) vkCmdFillBuffer(cmd, buffer, 0, VK_WHOLE_SIZE, 0);
        }
        barrier(cmd, stack);
    }

    /** Makes every earlier write visible to later shaders, of any stage. */
    void barrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default()
                .srcStageMask(SHADER_STAGES | VK_PIPELINE_STAGE_2_TRANSFER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(SHADER_STAGES | VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT | VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    private static void destroy(Gpu g) {
        if (g.pixels != null) MemoryUtil.memFree(g.pixels);
        vkDestroyImageView(Vulkan.getDevice(), g.view, null);
        MemoryManager.freeImage(g.image, g.memory);
    }

    void destroy() {
        VkDevice device = Vulkan.getDevice();
        for (Gpu g : images) if (g != null) destroy(g);
        for (int i = 0; i < buffers.length; i++) MemoryManager.freeBuffer(buffers[i], bufferMemory[i]);
        for (long[] s : staging) MemoryManager.freeBuffer(s[0], s[1]);
        vkDestroySampler(device, linearSampler, null);
        vkDestroySampler(device, nearestSampler, null);
        vkDestroyDescriptorPool(device, pool, null);
        vkDestroyDescriptorSetLayout(device, setLayout, null);
        vkDestroyDescriptorSetLayout(device, emptyLayout, null);
    }
}
