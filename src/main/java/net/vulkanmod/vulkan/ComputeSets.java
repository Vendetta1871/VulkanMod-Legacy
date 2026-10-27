package net.vulkanmod.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Descriptor sets for simple compute passes: binding 0 = combined image sampler (input),
 * binding 1 = storage image (output). Owns its layout and pool.
 */
final class ComputeSets {

    final long layout;
    private final long pool;
    private final long[] sets;

    private static long linearClamp, nearestClamp;

    ComputeSets(int count) {
        VkDevice device = Vulkan.getDevice();
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, p), "set layout");
            layout = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(count);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(count);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(count).pPoolSizes(sizes), null, p), "descriptor pool");
            pool = p.get(0);

            LongBuffer layouts = stack.mallocLong(count);
            for (int i = 0; i < count; i++) layouts.put(i, layout);
            LongBuffer out = stack.mallocLong(count);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "descriptor sets");
            sets = new long[count];
            for (int i = 0; i < count; i++) sets[i] = out.get(i);
        }
    }

    long set(int i) {
        return sets[i];
    }

    /** Points set {@code i} at an input (sampled in {@code inputLayout}) and an output (GENERAL). */
    void write(int i, long sampler, long inputView, int inputLayout, long outputView) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer in = VkDescriptorImageInfo.calloc(1, stack);
            in.get(0).sampler(sampler).imageView(inputView).imageLayout(inputLayout);
            VkDescriptorImageInfo.Buffer out = VkDescriptorImageInfo.calloc(1, stack);
            out.get(0).imageView(outputView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(sets[i]).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(in);
            writes.get(1).sType$Default().dstSet(sets[i]).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(out);
            vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);
        }
    }

    void bind(VkCommandBuffer cmd, long pipelineLayout, int i) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(sets[i]), null);
        }
    }

    void destroy() {
        VkDevice device = Vulkan.getDevice();
        vkDestroyDescriptorPool(device, pool, null);
        vkDestroyDescriptorSetLayout(device, layout, null);
    }

    /** Linear, clamp-to-edge, no mips (gathers and filtered reads). */
    static long linearClampSampler() {
        if (linearClamp == 0L) linearClamp = createSampler(VK_FILTER_LINEAR);
        return linearClamp;
    }

    /** Nearest, clamp-to-edge (texelFetch of formats without linear filtering, e.g. depth). */
    static long nearestClampSampler() {
        if (nearestClamp == 0L) nearestClamp = createSampler(VK_FILTER_NEAREST);
        return nearestClamp;
    }

    static void destroySamplers() {
        VkDevice device = Vulkan.getDevice();
        if (linearClamp != 0L) vkDestroySampler(device, linearClamp, null);
        if (nearestClamp != 0L) vkDestroySampler(device, nearestClamp, null);
        linearClamp = nearestClamp = 0L;
    }

    private static long createSampler(int filter) {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSampler(Vulkan.getDevice(), info, null, p), "sampler");
            return p.get(0);
        }
    }

    static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new RuntimeException("Compute: failed to create " + what + ": " + result);
    }
}
