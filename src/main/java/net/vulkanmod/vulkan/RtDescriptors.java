package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.rt.RtScene;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Shared descriptor layout of the RT passes, with sets per pass and frame slot:
 * 0 TLAS, 1 storage image (output or scene color), 2 per-instance vertex addresses,
 * 3 block atlas, 4 scene depth, 5 storage image (opaque-only scene copy),
 * 6-8 block lights (list, cell grid, cell indices, see RtLights),
 * 9-15 storage images of the lighting pass (history read/write, aux, normals, GI read/write, aux2),
 * 16 material atlas (LabPBR specular, see RtMaterials), 17-18 ReSTIR reservoirs (read, write),
 * 19-21 NRD inputs (block light, indirect light, view z).
 * Bindings a pass does not use stay unwritten.
 */
final class RtDescriptors {

    static final int PASSES = 4, SLOTS = 8;
    static final int PASS_DEBUG = 0, PASS_LIGHTING = 1, PASS_REFLECTIONS = 2, PASS_REFLECTIONS_OPAQUE = 3;

    private static long layout, pool;
    private static final long[][] sets = new long[PASSES][SLOTS];

    private RtDescriptors() {}

    static long layout() {
        if (layout == 0L) create();
        return layout;
    }

    private static void create() {
        VkDevice device = Vulkan.getDevice();
        int raygen = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
        int hits = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
        int[][] bindings = {
                {VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, hits},
                {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, hits},
                {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, hits},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, raygen},
        };
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(bindings.length, stack);
            for (int i = 0; i < bindings.length; i++) {
                b.get(i).binding(i).descriptorType(bindings[i][0]).descriptorCount(1).stageFlags(bindings[i][1]);
                sizes.get(i).type(bindings[i][0]).descriptorCount(PASSES * SLOTS);
            }
            RtPipeline.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, p), "RT set layout");
            layout = p.get(0);
            RtPipeline.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(PASSES * SLOTS).pPoolSizes(sizes), null, p), "RT descriptor pool");
            pool = p.get(0);

            LongBuffer layouts = stack.mallocLong(PASSES * SLOTS);
            for (int i = 0; i < PASSES * SLOTS; i++) layouts.put(i, layout);
            LongBuffer out = stack.mallocLong(PASSES * SLOTS);
            RtPipeline.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "RT descriptor sets");
            for (int i = 0; i < PASSES * SLOTS; i++) sets[i / SLOTS][i % SLOTS] = out.get(i);
        }
    }

    /**
     * Writes and returns the set of {@code pass} for the current frame slot (its previous
     * use retired with the slot's fence). Views equal to 0 are left unwritten.
     */
    static long write(int pass, long storageView, long depthView, long opaqueView) {
        return write(pass, storageView, depthView, opaqueView, null);
    }

    /** As {@link #write(int, long, long, long)}, plus the lighting pass's lights and images 9-15. */
    static long write(int pass, long storageView, long depthView, long opaqueView, long[] lightingViews) {
        if (layout == 0L) create();
        long set = sets[pass][Drawer.getCurrentFrame() % SLOTS];
        VulkanImage atlas = net.vulkanmod.render.TextureBridge.getAtlasImage();
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(22, stack);
            int n = 0;

            VkWriteDescriptorSetAccelerationStructureKHR as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType$Default().pAccelerationStructures(stack.longs(RtScene.tlas()));
            writes.get(n++).sType$Default().pNext(as.address()).dstSet(set).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);

            n = image(writes, n, set, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0L, storageView, VK_IMAGE_LAYOUT_GENERAL, stack);

            VkDescriptorBufferInfo.Buffer data = VkDescriptorBufferInfo.calloc(1, stack);
            data.get(0).buffer(RtScene.instanceDataBuffer()).offset(0).range(RtScene.instanceDataSize());
            writes.get(n++).sType$Default().dstSet(set).dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(data);

            if (atlas != null) {
                n = image(writes, n, set, 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, ComputeSets.nearestClampSampler(),
                        atlas.getTextureImageView(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, stack);
                VulkanImage materials = net.vulkanmod.vulkan.rt.RtMaterials.image();
                n = image(writes, n, set, 16, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, ComputeSets.nearestClampSampler(),
                        (materials != null ? materials : atlas).getTextureImageView(),
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, stack);
            }
            n = image(writes, n, set, 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, ComputeSets.nearestClampSampler(),
                    depthView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, stack);
            n = image(writes, n, set, 5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0L, opaqueView, VK_IMAGE_LAYOUT_GENERAL, stack);

            if (lightingViews != null) {
                long[][] buffers = {
                        {net.vulkanmod.vulkan.rt.RtLights.lightBuffer(), net.vulkanmod.vulkan.rt.RtLights.lightBytes()},
                        {net.vulkanmod.vulkan.rt.RtLights.gridBuffer(), net.vulkanmod.vulkan.rt.RtLights.gridBytes()},
                        {net.vulkanmod.vulkan.rt.RtLights.indexBuffer(), net.vulkanmod.vulkan.rt.RtLights.indexBytes()},
                };
                for (int i = 0; i < 3; i++) {
                    VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                    info.get(0).buffer(buffers[i][0]).offset(0).range(buffers[i][1]);
                    writes.get(n++).sType$Default().dstSet(set).dstBinding(6 + i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
                }
                for (int i = 0; i < lightingViews.length; i++) {
                    // Views 0-6 go to bindings 9-15, the rest from 17 (16 is the material atlas).
                    n = image(writes, n, set, i < 7 ? 9 + i : 10 + i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0L, lightingViews[i],
                            VK_IMAGE_LAYOUT_GENERAL, stack);
                }
            }

            writes.limit(n);
            vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);
        }
        return set;
    }

    private static int image(VkWriteDescriptorSet.Buffer writes, int n, long set, int binding, int type,
                             long sampler, long view, int layout, MemoryStack stack) {
        if (view == 0L) return n;
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(sampler).imageView(view).imageLayout(layout);
        writes.get(n).sType$Default().dstSet(set).dstBinding(binding).descriptorType(type)
                .descriptorCount(1).pImageInfo(info);
        return n + 1;
    }

    static void destroy() {
        if (layout == 0L) return;
        vkDestroyDescriptorPool(Vulkan.getDevice(), pool, null);
        vkDestroyDescriptorSetLayout(Vulkan.getDevice(), layout, null);
        layout = pool = 0L;
    }
}
