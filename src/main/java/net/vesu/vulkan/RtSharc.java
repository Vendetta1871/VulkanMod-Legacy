package net.vesu.vulkan;

import net.vesu.Env;
import net.vesu.Initializer;
import net.vesu.vulkan.rt.RtBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Radiance cache in a spatial hash grid after NVIDIA's SHaRC, the cache RTX Remix ends its
 * paths in (shaders rt/sharc*). Per frame, before the lighting pass: trace short paths from
 * one pixel of every TILE x TILE tile (sharc_update.rgen), adding the radiance of each vertex
 * to its voxel, then blend the sums into the voxels and evict stale ones (sharc_resolve.comp).
 * The lighting pass's indirect rays read the voxel at their hit (RT set bindings 37-39).
 *
 * <p>VESU_SHARC=0 turns it off (the DDGI probes give the indirect light again);
 * VESU_SHARC_TILE sets the tile edge (default 4), VESU_SHARC_LOG2 the voxel capacity
 * (default 2^22 voxels, 144 MB).</p>
 */
final class RtSharc {

    static final boolean ENABLED = !"0".equals(Env.get("VESU_SHARC"));
    private static final int TILE = Math.max(1, Math.min(16, envInt("VESU_SHARC_TILE", 4)));
    private static final int CAPACITY = 1 << Math.max(16, Math.min(24, envInt("VESU_SHARC_LOG2", 22)));

    private static RtBuffer keys, voxels, accum;
    private static boolean clearPending;
    private static Object world;
    private static long originX = Long.MIN_VALUE, originY, originZ;

    private static RtPipeline update;
    private static ComputePipeline resolve;
    private static long setLayout, pool;
    private static final long[] sets = new long[RtDescriptors.SLOTS];

    private RtSharc() {}

    private static int envInt(String name, int fallback) {
        String s = Env.get(name);
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Allocates the cache on first use (the lighting pass always binds it) and clears it when
     * the world or the light origin (which anchors the voxel grid) changed. {@code ox, oy, oz}:
     * the integer light origin. Returns true when the cache runs this frame.
     */
    static boolean begin(long ox, long oy, long oz, boolean wanted) {
        if (keys == null) {
            int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            keys = RtBuffer.create(CAPACITY * 4L, usage, false);
            voxels = RtBuffer.create(CAPACITY * 16L, usage, false);
            accum = RtBuffer.create(CAPACITY * 16L, usage, false);
            clearPending = true;
            Initializer.LOGGER.info("[rt] SHaRC radiance cache: {} voxels, paths every {}x{} pixels", CAPACITY, TILE, TILE);
        }
        Object w = net.minecraft.client.Minecraft.getMinecraft().world;
        if (w != world || ox != originX || oy != originY || oz != originZ) {
            world = w;
            originX = ox;
            originY = oy;
            originZ = oz;
            clearPending = true;
        }
        return ENABLED && wanted;
    }

    static long keyBuffer() { return keys.buffer; }
    static long keyBytes() { return keys.size; }
    static long voxelBuffer() { return voxels.buffer; }
    static long voxelBytes() { return voxels.size; }
    static long accumBuffer() { return accum.buffer; }
    static long accumBytes() { return accum.size; }

    /** Records update and resolve; {@code rtSet} and {@code push}: the lighting pass's. */
    static void record(VkCommandBuffer cmd, MemoryStack stack, long rtSet, ByteBuffer push, int pushBytes,
                       int width, int height) {
        prepare(pushBytes);
        if (clearPending) clear(cmd, stack);
        // The previous frame's readers (lighting pass) and resolve before the new writers.
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
        update.trace(cmd, rtSet, push, (width + TILE - 1) / TILE, (height + TILE - 1) / TILE);
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
        long set = sets[Drawer.getCurrentFrame() % RtDescriptors.SLOTS];
        writeComputeSet(set, stack);
        resolve.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, resolve.layout(), 0, stack.longs(set), null);
        resolve.dispatch(cmd, CAPACITY / 64, 1, 1);
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_READ_BIT);
    }

    /** Fresh or invalidated cache when it does not run: still emptied for the lighting pass. */
    static void clearIfNeeded(VkCommandBuffer cmd, MemoryStack stack) {
        if (clearPending) clear(cmd, stack);
    }

    private static void clear(VkCommandBuffer cmd, MemoryStack stack) {
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT);
        for (RtBuffer b : new RtBuffer[]{keys, voxels, accum}) vkCmdFillBuffer(cmd, b.buffer, 0, VK_WHOLE_SIZE, 0);
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
        clearPending = false;
    }

    private static void prepare(int pushBytes) {
        if (update == null) {
            update = new RtPipeline(RtDescriptors.layout(), pushBytes, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/sharc_update.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit"),
                            new RtPipeline.HitGroup(null, "rt/shadow.rahit")});
        }
        if (resolve == null) {
            createSets();
            ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile("rt/sharc_resolve.comp",
                    ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
            if (spv == null) throw new RuntimeException("SHaRC resolve shader failed to compile");
            resolve = new ComputePipeline(spv, new long[]{setLayout}, 0);
            spv.free();
        }
    }

    /** Compute set: 0 keys, 1 voxels, 2 sums. */
    private static void createSets() {
        VkDevice device = Vulkan.getDevice();
        try (MemoryStack stack = stackPush()) {
            LongBuffer ptr = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            RtPipeline.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, ptr), "SHaRC set layout");
            setLayout = ptr.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3 * RtDescriptors.SLOTS);
            RtPipeline.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RtDescriptors.SLOTS).pPoolSizes(sizes), null, ptr), "SHaRC pool");
            pool = ptr.get(0);
            LongBuffer layouts = stack.mallocLong(RtDescriptors.SLOTS);
            for (int i = 0; i < RtDescriptors.SLOTS; i++) layouts.put(i, setLayout);
            LongBuffer out = stack.mallocLong(RtDescriptors.SLOTS);
            RtPipeline.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "SHaRC sets");
            for (int i = 0; i < RtDescriptors.SLOTS; i++) sets[i] = out.get(i);
        }
    }

    private static void writeComputeSet(long set, MemoryStack stack) {
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
        RtBuffer[] buffers = {keys, voxels, accum};
        for (int i = 0; i < 3; i++) {
            VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
            info.get(0).buffer(buffers[i].buffer).offset(0).range(buffers[i].size);
            writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(info);
        }
        vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess,
                                long dstStage, long dstAccess) {
        VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess);
        VkCompat.pipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    /** The device must be idle. */
    static void destroy() {
        for (RtBuffer b : new RtBuffer[]{keys, voxels, accum}) {
            if (b != null) RtBuffer.defer(b::destroyNow);
        }
        keys = voxels = accum = null;
        if (update != null) { update.destroy(); update = null; }
        if (resolve != null) {
            resolve.cleanUp();
            resolve = null;
            vkDestroyDescriptorPool(Vulkan.getDevice(), pool, null);
            vkDestroyDescriptorSetLayout(Vulkan.getDevice(), setLayout, null);
            pool = setLayout = 0L;
        }
        world = null;
        originX = Long.MIN_VALUE;
    }
}
