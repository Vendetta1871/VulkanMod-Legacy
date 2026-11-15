package net.vesu.vulkan;

import net.vesu.Env;
import net.vesu.Initializer;
import net.vesu.render.CameraHistory;
import net.vesu.vulkan.rt.RtBuffer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Indirect light from DDGI probes (Majercik et al., JCGT 2019; relocation and
 * classification after Majercik et al., JCGT 2021); the shaders are in rt/ddgi*.
 * Per frame, before the lighting pass: reset the probe slots the scrolling grid handed to new
 * probes (ddgi_prepare.comp), trace the probe rays (ddgi_trace.rgen), blend them into the
 * irradiance and distance atlases (ddgi_blend.comp), then relocate and classify the probes
 * (ddgi_relocate.comp). lighting.rgen samples the atlases for every pixel.
 *
 * <p>Cascades: camera-centred grids of the same probe counts, spacing doubling per cascade,
 * as many as the indirect light range needs. Overrides for tuning: VESU_DDGI=0 (the old
 * per-pixel rays), VESU_DDGI_PROBES="x y z", VESU_DDGI_SPACING, VESU_DDGI_RAYS.</p>
 */
final class RtDdgi {

    static final boolean ENABLED = !"0".equals(Env.get("VESU_DDGI"));
    private static final int[] COUNTS = counts();
    private static final float SPACING = envFloat("VESU_DDGI_SPACING", 1.5f);
    private static final int BLEND_RAYS = Math.max(16, Math.min(256, (int) envFloat("VESU_DDGI_RAYS", 64)));
    private static final int FIXED_RAYS = 16, RAYS = BLEND_RAYS + FIXED_RAYS;
    private static final float HYSTERESIS = 0.97f;
    /** Probes closer than this to a surface move away from it, in spacings. */
    private static final float MIN_FRONTFACE = 0.3f;
    private static final int MAX_CASCADES = 8, IRRADIANCE_TEXELS = 8, DISTANCE_TEXELS = 16;
    private static final int VOLUME_BYTES = 112 + MAX_CASCADES * 32, VOLUME_STRIDE = 512;
    private static final int ATLAS_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private static final StorageImage irradiance = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, ATLAS_USAGE);
    private static final StorageImage distance = new StorageImage(VK_FORMAT_R32G32_SFLOAT, ATLAS_USAGE);
    private static RtBuffer probes, rays, volume;
    private static int cascades;
    private static boolean created, resetPending;
    private static Object world;

    private static RtPipeline trace;
    private static ComputePipeline prepare, blend, relocate;
    private static long setLayout, pool;
    private static final long[] sets = new long[RtDescriptors.SLOTS];
    private static final Matrix4f rotation = new Matrix4f();
    private static final Quaternionf quat = new Quaternionf();
    private static int frame;

    private RtDdgi() {}

    private static int[] counts() {
        String s = Env.get("VESU_DDGI_PROBES");
        if (s != null) {
            String[] p = s.trim().split("\\s+");
            if (p.length == 3) {
                return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
            }
        }
        return new int[]{24, 16, 24};
    }

    private static float envFloat(String name, float fallback) {
        String s = Env.get(name);
        try {
            return s == null ? fallback : Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Cascades whose finest-to-coarsest grids reach {@code range} blocks from the camera. */
    private static int cascadesFor(float range) {
        int k = 1;
        while (k < MAX_CASCADES && (COUNTS[0] - 1) * SPACING * (1 << (k - 1)) * 0.5f < range) k++;
        return k;
    }

    private static int probeCount() {
        return COUNTS[0] * COUNTS[1] * COUNTS[2] * cascades;
    }

    /**
     * Sizes the resources for {@code range} (blocks; at least one cascade so the lighting
     * pass always has its bindings) and writes this frame's volume constants. Returns true
     * when the probes run this frame.
     */
    static boolean begin(float range) {
        int k = cascadesFor(Math.max(range, 1.0f));
        if (!created || k != cascades) allocate(k);
        Object w = net.minecraft.client.Minecraft.getMinecraft().world;
        if (w != world) {
            world = w;
            resetPending = true;
        }
        writeVolume();
        return ENABLED && range > 0.0f;
    }

    private static void allocate(int k) {
        // The atlases go at once: no frame in flight may still read them.
        if (created) Vulkan.waitIdle();
        destroyResources();
        cascades = k;
        int row = COUNTS[0] * COUNTS[2], column = COUNTS[1] * k;
        irradiance.ensure(row * (IRRADIANCE_TEXELS + 2), column * (IRRADIANCE_TEXELS + 2));
        distance.ensure(row * (DISTANCE_TEXELS + 2), column * (DISTANCE_TEXELS + 2));
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        probes = RtBuffer.create(probeCount() * 32L, usage, false);
        rays = RtBuffer.create((long) probeCount() * RAYS * 8L, usage, false);
        volume = RtBuffer.create((long) VOLUME_STRIDE * RtDescriptors.SLOTS, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        created = true;
        resetPending = true;
        clearPending = true;
        Initializer.LOGGER.info("[rt] DDGI: {} cascades of {}x{}x{} probes, spacing {}, {} rays", k,
                COUNTS[0], COUNTS[1], COUNTS[2], SPACING, BLEND_RAYS);
    }

    private static boolean clearPending;

    private static void writeVolume() {
        ByteBuffer v = volume.mapped;
        int o = slot() * VOLUME_STRIDE;
        v.putInt(o, COUNTS[0]).putInt(o + 4, COUNTS[1]).putInt(o + 8, COUNTS[2]).putInt(o + 12, cascades);
        v.putInt(o + 16, BLEND_RAYS).putInt(o + 20, FIXED_RAYS).putInt(o + 24, RAYS).putInt(o + 28, frame);
        // A uniformly random rotation per frame (Shoemake): the blended rays cover the sphere
        // differently every frame.
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double u1 = r.nextDouble(), u2 = r.nextDouble() * 2.0 * Math.PI, u3 = r.nextDouble() * 2.0 * Math.PI;
        double a = Math.sqrt(1.0 - u1), b = Math.sqrt(u1);
        quat.set((float) (a * Math.sin(u2)), (float) (a * Math.cos(u2)), (float) (b * Math.sin(u3)),
                (float) (b * Math.cos(u3))).normalize();
        rotation.rotation(quat).get(o + 32, v);
        v.putFloat(o + 96, HYSTERESIS).putFloat(o + 100, resetPending ? 1.0f : 0.0f)
                .putFloat(o + 104, MIN_FRONTFACE).putFloat(o + 108, 0.0f);
        double cx = CameraHistory.camX(), cy = CameraHistory.camY(), cz = CameraHistory.camZ();
        for (int c = 0; c < MAX_CASCADES; c++) {
            int p = o + 112 + c * 32;
            float spacing = SPACING * (1 << Math.min(c, 20));
            long bx = (long) Math.floor(cx / spacing) - COUNTS[0] / 2;
            long by = (long) Math.floor(cy / spacing) - COUNTS[1] / 2;
            long bz = (long) Math.floor(cz / spacing) - COUNTS[2] / 2;
            v.putFloat(p, (float) (bx * spacing - cx)).putFloat(p + 4, (float) (by * spacing - cy))
                    .putFloat(p + 8, (float) (bz * spacing - cz)).putFloat(p + 12, spacing);
            v.putInt(p + 16, (int) bx).putInt(p + 20, (int) by).putInt(p + 24, (int) bz).putInt(p + 28, 0);
        }
    }

    private static int slot() {
        return Drawer.getCurrentFrame() % RtDescriptors.SLOTS;
    }

    // --- descriptors of the RT set (RtDescriptors bindings 24-28) ---

    static long irradianceView() { return irradiance.view; }
    static long distanceView() { return distance.view; }
    static long probeBuffer() { return probes.buffer; }
    static long probeBytes() { return probes.size; }
    static long rayBuffer() { return rays.buffer; }
    static long rayBytes() { return rays.size; }
    static long volumeBuffer() { return volume.buffer; }
    static long volumeOffset() { return (long) slot() * VOLUME_STRIDE; }
    static long volumeBytes() { return VOLUME_BYTES; }

    /**
     * Records the probe passes. {@code rtSet}: the lighting pass's RT set of this frame (with
     * bindings 24-28); {@code push}: its push constants (sun, sky).
     */
    static void record(VkCommandBuffer cmd, MemoryStack stack, long rtSet, ByteBuffer push, int pushBytes) {
        prepare(pushBytes);
        if (clearPending) clear(cmd, stack);
        // The previous frame's readers (lighting, probe rays) before this frame's writers.
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
        long set = sets[slot()];
        writeComputeSet(set, stack);
        int count = probeCount();

        prepare.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, prepare.layout(), 0, stack.longs(set), null);
        prepare.dispatch(cmd, (count + 63) / 64, 1, 1);
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_READ_BIT);

        trace.trace(cmd, rtSet, push, RAYS, count);
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_WRITE_BIT
                        | VK_ACCESS_2_SHADER_READ_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);

        blend.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, blend.layout(), 0, stack.longs(set), null);
        blend.dispatch(cmd, COUNTS[0] * COUNTS[2], COUNTS[1] * cascades, 2);
        // Relocation rewrites the probe states the blend reads.
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
        relocate.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, relocate.layout(), 0, stack.longs(set), null);
        relocate.dispatch(cmd, (count + 63) / 64, 1, 1);
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_READ_BIT);
        resetPending = false;
        frame++;
    }

    /** New resources: atlases to GENERAL and zero, buffers zero (every probe resets). */
    private static void clear(VkCommandBuffer cmd, MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer init = VkImageMemoryBarrier2.calloc(2, stack);
        StorageImage[] images = {irradiance, distance};
        for (int i = 0; i < 2; i++) {
            FrameTargets.imageBarrier(init.get(i), images[i].image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        }
        FrameTargets.pipelineBarrier(cmd, init, stack);
        VkClearColorValue zero = VkClearColorValue.calloc(stack);
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        for (StorageImage img : images) vkCmdClearColorImage(cmd, img.image, VK_IMAGE_LAYOUT_GENERAL, zero, range);
        vkCmdFillBuffer(cmd, probes.buffer, 0, VK_WHOLE_SIZE, 0);
        vkCmdFillBuffer(cmd, rays.buffer, 0, VK_WHOLE_SIZE, 0);
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
        clearPending = false;
    }

    /** First use of fresh resources when the probes do not run: still cleared for the lighting pass. */
    static void clearIfNeeded(VkCommandBuffer cmd, MemoryStack stack) {
        if (clearPending) clear(cmd, stack);
    }

    private static void prepare(int pushBytes) {
        if (trace == null) {
            trace = new RtPipeline(RtDescriptors.layout(), pushBytes, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/ddgi_trace.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit"),
                            new RtPipeline.HitGroup(null, "rt/shadow.rahit")});
        }
        if (prepare == null) {
            createSets();
            prepare = compute("rt/ddgi_prepare.comp");
            blend = compute("rt/ddgi_blend.comp");
            relocate = compute("rt/ddgi_relocate.comp");
        }
    }

    private static ComputePipeline compute(String file) {
        ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile(file, ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
        if (spv == null) throw new RuntimeException("DDGI shader " + file + " failed to compile");
        ComputePipeline p = new ComputePipeline(spv, new long[]{setLayout}, 0);
        spv.free();
        return p;
    }

    /** Compute set: 0 rays, 1 probes, 2 volume constants, 3 irradiance atlas, 4 distance atlas. */
    private static void createSets() {
        VkDevice device = Vulkan.getDevice();
        int[] types = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
        try (MemoryStack stack = stackPush()) {
            LongBuffer ptr = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                sizes.get(i).type(types[i]).descriptorCount(RtDescriptors.SLOTS);
            }
            RtPipeline.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, ptr), "DDGI set layout");
            setLayout = ptr.get(0);
            RtPipeline.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RtDescriptors.SLOTS).pPoolSizes(sizes), null, ptr), "DDGI pool");
            pool = ptr.get(0);
            LongBuffer layouts = stack.mallocLong(RtDescriptors.SLOTS);
            for (int i = 0; i < RtDescriptors.SLOTS; i++) layouts.put(i, setLayout);
            LongBuffer out = stack.mallocLong(RtDescriptors.SLOTS);
            RtPipeline.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "DDGI sets");
            for (int i = 0; i < RtDescriptors.SLOTS; i++) sets[i] = out.get(i);
        }
    }

    private static void writeComputeSet(long set, MemoryStack stack) {
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
        long[][] buffers = {{rays.buffer, 0, rays.size}, {probes.buffer, 0, probes.size},
                {volume.buffer, volumeOffset(), VOLUME_BYTES}};
        for (int i = 0; i < 3; i++) {
            VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
            info.get(0).buffer(buffers[i][0]).offset(buffers[i][1]).range(buffers[i][2]);
            writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(info);
        }
        long[] views = {irradiance.view, distance.view};
        for (int i = 0; i < 2; i++) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(3 + i).sType$Default().dstSet(set).dstBinding(3 + i).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
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

    private static void destroyResources() {
        irradiance.destroy();
        distance.destroy();
        for (RtBuffer b : new RtBuffer[]{probes, rays, volume}) {
            if (b != null) RtBuffer.defer(b::destroyNow);
        }
        probes = rays = volume = null;
        created = false;
    }

    /** The device must be idle. */
    static void destroy() {
        destroyResources();
        if (trace != null) { trace.destroy(); trace = null; }
        if (prepare != null) {
            prepare.cleanUp();
            blend.cleanUp();
            relocate.cleanUp();
            prepare = blend = relocate = null;
            vkDestroyDescriptorPool(Vulkan.getDevice(), pool, null);
            vkDestroyDescriptorSetLayout(Vulkan.getDevice(), setLayout, null);
            pool = setLayout = 0L;
        }
        world = null;
    }
}
