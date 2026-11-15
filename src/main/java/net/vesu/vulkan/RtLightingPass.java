package net.vesu.vulkan;

import net.vesu.Env;

import net.vesu.Initializer;
import net.vesu.render.CameraHistory;
import net.vesu.vulkan.rt.RtLights;
import net.vesu.vulkan.rt.RtScene;
import org.joml.Matrix4f;
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
 * Hybrid ray-traced lighting on the raster scene (T8): sun/moon shadows, shadowed,
 * directional block light (torches, glowstone, lava...) and indirect light from the DDGI probes (RtDdgi;
 * VESU_DDGI=0 falls back to one per-pixel bounce ray). lighting.rgen traces and accumulates the block
 * light over frames and samples the probes; rt_composite.comp filters the block light and relights
 * the scene color in place, before translucent geometry and the upscaler.
 * Strengths: Config.rtShadowStrength, rtBlockLight, rtIndirect (VESU_RT_SHADOWS, _BLOCK, _GI).
 */
final class RtLightingPass {

    private static final int RT_PUSH = 256, COMPOSITE_PUSH = 208;
    /** VESU_DEBUG_VIEW=rtlight / rtgi: shows the filtered RT block light / indirect light alone. */
    private static final float DEBUG = "rtlight".equalsIgnoreCase(Env.get("VESU_DEBUG_VIEW")) ? 1.0f
            : "rtgi".equalsIgnoreCase(Env.get("VESU_DEBUG_VIEW")) ? 2.0f : 0.0f;

    /** Whether NRD denoised this frame's lighting (its inputs then serve the reflections too). */
    static boolean nrdThisFrame;
    /**
     * Whether this frame's lighting was path traced and denoised by Ray Reconstruction
     * (Rr2Denoiser): the opaque reflections are part of it then.
     */
    static boolean ptThisFrame;
    /** VESU_PT=0: keep the hybrid passes and NRD even where Ray Reconstruction is available. */
    private static final long START = System.nanoTime();
    private static final boolean PT_ALLOWED = !"0".equals(Env.get("VESU_PT"));
    /** VESU_DEBUG_VIEW=ptraw: the path tracer's noisy radiance, without Ray Reconstruction's output. */
    private static final boolean PT_RAW = "ptraw".equalsIgnoreCase(Env.get("VESU_DEBUG_VIEW"));

    /** NRD's common inputs of this frame: motion vectors, normals + roughness, view z ({image, format, layout}). */
    static long[][] nrdCommonInputs() {
        return new long[][]{{MotionVectorPass.motionVectors.image, MotionVectorPass.motionVectors.format, 1},
                {normals.image, normals.format, 0}, {viewZ.image, viewZ.format, 0}};
    }

    private static RtPipeline rtPipeline, ptPipeline;
    private static ComputePipeline composite, resolve;
    private static long resolveLayout, resolvePool;
    private static final long[] resolveSets = new long[RtDescriptors.SLOTS];
    private static long setLayout, pool;
    private static final long[] sets = new long[RtDescriptors.SLOTS];

    private static final StorageImage[] history = {
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT)};
    private static final StorageImage aux = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
    private static final int NRD_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    // Normals double as NRD's IN_NORMAL_ROUGHNESS (RGBA8: n * 0.5 + 0.5, linear roughness).
    private static final StorageImage normals = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM, NRD_USAGE);
    /** NRD inputs (this frame's samples) and outputs. */
    private static final StorageImage blockRaw = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, NRD_USAGE),
            giRaw = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, NRD_USAGE),
            viewZ = new StorageImage(VK_FORMAT_R32_SFLOAT, NRD_USAGE),
            blockDenoised = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, NRD_USAGE),
            giDenoised = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, NRD_USAGE);
    private static final StorageImage albedo = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT);
    /** SIGMA (NRD) inputs and output: the sun shadow's penumbra and translucency, the denoised shadow. */
    private static final StorageImage penumbra = new StorageImage(VK_FORMAT_R16_SFLOAT, NRD_USAGE),
            translucency = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM, NRD_USAGE),
            shadowOut = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM, NRD_USAGE);
    /** 1x1 placeholders bound where a path does not use an image (see record). */
    private static final StorageImage unused16 = new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            unused32 = new StorageImage(VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            unused8 = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT),
            unusedR16 = new StorageImage(VK_FORMAT_R16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
    private static final StorageImage aux2 = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT);
    /** ReSTIR reservoirs per pixel: light index (bits), W, M. */
    private static final StorageImage[] reservoirs = {
            new StorageImage(VK_FORMAT_R32G32B32A32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            new StorageImage(VK_FORMAT_R32G32B32A32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT)};
    private static int lightGeneration = -1;
    private static final StorageImage[] giHistory = {
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT),
            new StorageImage(VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT)};
    private static int parity, frame;
    private static boolean historyValid;

    private static final Matrix4f invJittered = new Matrix4f();
    private static final Matrix4f prevRel = new Matrix4f();

    private RtLightingPass() {}

    /** Recording id (FrameTargets.recordingId) of the last frame this pass traced. */
    private static long tracedFrame = -1;
    private static float tracedRange;

    /**
     * The normals image when this frame's lighting wrote it for every pixel within
     * {@code range} blocks (its a: terrain roughness, see lighting.rgen), else 0.
     */
    static long normalsCovering(float range) {
        return tracedFrame == FrameTargets.recordingId() && range <= tracedRange ? normals.view : 0L;
    }

    static void record(VkCommandBuffer cmd, MemoryStack stack) {
        if (!RtScene.ready() || !RtLights.ready() || net.vesu.render.TextureBridge.getAtlasImage() == null) return;
        prepare();
        RenderTarget scene = FrameTargets.scene;
        int w = scene.width(), h = scene.height();
        if (pathTraced(w, h)) {
            recordPathTraced(cmd, stack, scene, w, h);
            return;
        }
        ptThisFrame = false;
        boolean nrd = NrdDenoiser.ensure(w, h);
        nrdThisFrame = nrd;
        // R5: the own history only without NRD, NRD's inputs and outputs only with it; the
        // shaders skip the other set (bound to 1x1 placeholders, never accessed).
        StorageImage[] needed = nrd
                ? new StorageImage[]{aux, normals, giHistory[0], giHistory[1], aux2, reservoirs[0], reservoirs[1],
                        albedo, blockRaw, giRaw, viewZ, blockDenoised, giDenoised, penumbra, translucency, shadowOut,
                        unused16, unused32, unused8, unusedR16}
                : new StorageImage[]{aux, normals, giHistory[0], giHistory[1], aux2, reservoirs[0], reservoirs[1],
                        albedo, history[0], history[1], unused16, unused32, unused8, unusedR16};
        java.util.ArrayList<StorageImage> created = new java.util.ArrayList<>();
        for (StorageImage img : needed) {
            boolean placeholder = img == unused16 || img == unused32 || img == unused8 || img == unusedR16;
            if (img.ensure(placeholder ? 1 : w, placeholder ? 1 : h)) created.add(img);
        }
        if (!created.isEmpty()) historyValid = false;
        parity ^= 1;
        StorageImage cur = nrd ? unused16 : history[parity], prev = nrd ? unused16 : history[parity ^ 1];
        StorageImage giCur = giHistory[parity], giPrev = giHistory[parity ^ 1];
        StorageImage blockIn = nrd ? blockRaw : unused16, giIn = nrd ? giRaw : unused16,
                viewZIn = nrd ? viewZ : unused32;

        // Inputs and the previous frame's readers/writers of these images.
        FrameTargets.sceneDepthToShaderRead(cmd, stack);
        if (!created.isEmpty()) {
            VkImageMemoryBarrier2.Buffer init = VkImageMemoryBarrier2.calloc(created.size(), stack);
            for (int i = 0; i < created.size(); i++) {
                FrameTargets.imageBarrier(init.get(i), created.get(i).image, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0,
                        VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                        VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            }
            FrameTargets.pipelineBarrier(cmd, init, stack);
        }
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

        float gi = Initializer.CONFIG.effectiveRtIndirect();
        float giRange = gi > 0.0f ? Initializer.CONFIG.effectiveRtIndirectDistance() * 16.0f : 0.0f;
        float[] origin = RtLights.cameraOffset(CameraHistory.camX(), CameraHistory.camY(), CameraHistory.camZ());
        boolean sharc = RtSharc.begin(Math.round(CameraHistory.camX() - origin[0]),
                Math.round(CameraHistory.camY() - origin[1]), Math.round(CameraHistory.camZ() - origin[2]), giRange > 0.0f);
        // The radiance cache replaces the DDGI probes (still bound, idle).
        boolean ddgi = RtDdgi.begin(giRange) && !sharc;
        long rtSet = RtDescriptors.write(RtDescriptors.PASS_LIGHTING, 0L, scene.depthView(), 0L,
                new long[]{prev.view, cur.view, aux.view, normals.view, giPrev.view, giCur.view, aux2.view,
                        reservoirs[parity ^ 1].view, reservoirs[parity].view, blockIn.view, giIn.view, viewZIn.view,
                        albedo.view});
        RtDescriptors.writeStorageImages(rtSet, 33, (nrd ? penumbra : unusedR16).view, (nrd ? translucency : unused8).view);

        boolean hasPrev = CameraHistory.hasPrevious() && historyValid;
        ByteBuffer c = lightingConstants(stack, w, h, hasPrev, giRange, nrd, ddgi);
        float[] sun = RtLighting.sun();
        boolean sky = RtLighting.hasSky();
        boolean day = sun[1] >= 0.0f;
        float s = day ? 1.0f : -1.0f;
        if (ddgi) {
            RtDdgi.record(cmd, stack, rtSet, c, RT_PUSH);
            GpuTimer.mark(cmd, GpuTimer.DDGI);
        } else {
            RtDdgi.clearIfNeeded(cmd, stack);
        }
        if (sharc) {
            RtSharc.record(cmd, stack, rtSet, c, RT_PUSH, w, h);
            GpuTimer.mark(cmd, GpuTimer.DDGI);
        } else {
            RtSharc.clearIfNeeded(cmd, stack);
        }
        rtPipeline.trace(cmd, rtSet, c, w, h);
        GpuTimer.mark(cmd, GpuTimer.LIGHT_TRACE);
        historyValid = true;
        tracedFrame = FrameTargets.recordingId();
        // Pixels past both ranges return before the primary ray (no normals written).
        tracedRange = Math.max(Initializer.CONFIG.effectiveRtShadowDistance() * 16.0f, giRange);

        if (nrd) {
            memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
            // NRD needs camera motion vectors now, before the scene phase ends.
            MotionVectorPass.record(cmd, FrameTargets.depthAspect(), stack);
            long[][] images = new long[10][];
            images[NrdDenoiser.MV] = new long[]{MotionVectorPass.motionVectors.image, MotionVectorPass.motionVectors.format, 1};
            images[NrdDenoiser.NORMAL_ROUGHNESS] = new long[]{normals.image, normals.format, 0};
            images[NrdDenoiser.VIEWZ] = new long[]{viewZ.image, viewZ.format, 0};
            images[NrdDenoiser.BLOCK_IN] = new long[]{blockRaw.image, blockRaw.format, 0};
            images[NrdDenoiser.BLOCK_OUT] = new long[]{blockDenoised.image, blockDenoised.format, 0};
            images[NrdDenoiser.GI_IN] = new long[]{giRaw.image, giRaw.format, 0};
            images[NrdDenoiser.GI_OUT] = new long[]{giDenoised.image, giDenoised.format, 0};
            images[NrdDenoiser.PENUMBRA] = new long[]{penumbra.image, penumbra.format, 0};
            images[NrdDenoiser.TRANSLUCENCY] = new long[]{translucency.image, translucency.format, 0};
            images[NrdDenoiser.SHADOW_OUT] = new long[]{shadowOut.image, shadowOut.format, 0};
            NrdDenoiser.denoise(cmd, images, !hasPrev, true,
                    sky ? new float[]{sun[0] * s, sun[1] * s, sun[2] * s} : null, stack);
            GpuTimer.mark(cmd, GpuTimer.LIGHT_DENOISE);
            memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        }

        // Composite: reads the RT outputs, writes the scene color.
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        long set = sets[Drawer.getCurrentFrame() % RtDescriptors.SLOTS];
        writeCompositeSet(set, scene, cur, giCur, nrd);
        ByteBuffer p = compositeConstants(stack, gi, nrd, ddgi);
        composite.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, composite.layout(), 0, stack.longs(set), null);
        vkCmdPushConstants(cmd, composite.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, p);
        composite.dispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    /** The path-traced mode applies: Ray Reconstruction is available at this render size. */
    static boolean pathTraced(int w, int h) {
        return PT_ALLOWED && Rr2Denoiser.ensure(w, h);
    }

    /** The range of this frame's path-traced translucent layer (blocks), 0 when it was not path traced. */
    static float pathTracedRange() {
        return ptThisFrame && tracedFrame == FrameTargets.recordingId() ? tracedRange : 0.0f;
    }

    /** Whether the next frames will be path traced (jitter for RR; checked before the scene). */
    static boolean pathTracingWanted() {
        return PT_ALLOWED && Rr2Denoiser.available();
    }

    /**
     * Path-traced lighting (pathtrace.rgen) denoised by Ray Reconstruction: the trace writes RR's
     * input planes, rt_pt_resolve.comp fills the sky and the vanilla-lit pixels, RR2 denoises and
     * anti-aliases, rt_pt_resolve.comp writes its output to the scene color.
     */
    private static void recordPathTraced(VkCommandBuffer cmd, MemoryStack stack, RenderTarget scene, int w, int h) {
        ptThisFrame = true;
        nrdThisFrame = false;
        StorageImage[] needed = {normals, giHistory[0], giHistory[1], reservoirs[0], reservoirs[1]};
        java.util.ArrayList<StorageImage> created = new java.util.ArrayList<>();
        for (StorageImage img : needed) if (img.ensure(w, h)) created.add(img);
        if (!created.isEmpty()) historyValid = false;
        parity ^= 1;
        StorageImage giCur = giHistory[parity], giPrev = giHistory[parity ^ 1];

        FrameTargets.sceneDepthToShaderRead(cmd, stack);
        if (!created.isEmpty()) {
            VkImageMemoryBarrier2.Buffer init = VkImageMemoryBarrier2.calloc(created.size(), stack);
            for (int i = 0; i < created.size(); i++) {
                FrameTargets.imageBarrier(init.get(i), created.get(i).image, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0,
                        VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                        VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            }
            FrameTargets.pipelineBarrier(cmd, init, stack);
        }
        float jx = CameraHistory.jitterX(), jy = CameraHistory.jitterY();
        // The previous frame's readers of the input buffer (RR, the resolve) are done before the header write.
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
        Rr2Denoiser.writeHeader(cmd, jx, jy, (float) ((System.nanoTime() - START) / 1e9 % 3600.0),
                (float) (CameraHistory.camX() % 1024.0), (float) (CameraHistory.camZ() % 1024.0), stack);
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

        float gi = Initializer.CONFIG.effectiveRtIndirect();
        float giRange = gi > 0.0f ? Initializer.CONFIG.effectiveRtIndirectDistance() * 16.0f : 0.0f;
        float range = Math.max(Initializer.CONFIG.effectiveRtShadowDistance() * 16.0f, giRange);
        float[] origin = RtLights.cameraOffset(CameraHistory.camX(), CameraHistory.camY(), CameraHistory.camZ());
        boolean sharc = RtSharc.begin(Math.round(CameraHistory.camX() - origin[0]),
                Math.round(CameraHistory.camY() - origin[1]), Math.round(CameraHistory.camZ() - origin[2]), giRange > 0.0f);
        RtDdgi.begin(0.0f);
        // Views 0-6 go to bindings 9-15, 7-8 to 17-18 (RtDescriptors); 0 leaves a binding unwritten.
        long rtSet = RtDescriptors.write(RtDescriptors.PASS_LIGHTING, 0L, scene.depthView(), 0L,
                new long[]{0L, 0L, 0L, normals.view, giPrev.view, giCur.view, 0L,
                        reservoirs[parity ^ 1].view, reservoirs[parity].view});
        RtDescriptors.writeStorageBuffer(rtSet, 40, Rr2Denoiser.inputBuffer(), 0L, Rr2Denoiser.inputBytes());

        boolean hasPrev = CameraHistory.hasPrevious() && historyValid;
        ByteBuffer c = lightingConstants(stack, w, h, hasPrev, giRange, false, false);
        boolean day = RtLighting.sun()[1] >= 0.0f;
        // pathtrace.rgen: range, night flag (the sky's sun), block light and indirect strengths.
        c.putFloat(144, range).putFloat(212, day ? 1.0f : -1.0f)
                .putFloat(220, Initializer.CONFIG.effectiveRtBlockLight()).putFloat(240, gi);
        RtDdgi.clearIfNeeded(cmd, stack);
        if (sharc) {
            RtSharc.record(cmd, stack, rtSet, c, RT_PUSH, w, h);
            GpuTimer.mark(cmd, GpuTimer.DDGI);
        } else {
            RtSharc.clearIfNeeded(cmd, stack);
        }
        ptPipeline.trace(cmd, rtSet, c, w, h);
        GpuTimer.mark(cmd, GpuTimer.LIGHT_TRACE);
        historyValid = true;
        tracedFrame = FrameTargets.recordingId();
        tracedRange = range;

        // Resolve before RR: the sky and vanilla-lit pixels into the radiance plane.
        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);
        long set = resolveSets[Drawer.getCurrentFrame() % RtDescriptors.SLOTS];
        writeResolveSet(set, scene);
        ByteBuffer p = compositeConstants(stack, gi, false, false);
        p.putFloat(92, Rr2Denoiser.outputWidth());
        p.putFloat(84, PT_RAW ? 1.0f : 0.0f);
        resolve.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, resolve.layout(), 0, stack.longs(set), null);
        p.putFloat(88, 0.0f);
        vkCmdPushConstants(cmd, resolve.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, p);
        resolve.dispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);

        // RR starts and ends with full memory barriers of its own.
        Rr2Denoiser.record(cmd, jx, jy, !hasPrev, stack);
        GpuTimer.mark(cmd, GpuTimer.LIGHT_DENOISE);

        resolve.bind(cmd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, resolve.layout(), 0, stack.longs(set), null);
        p.putFloat(88, 1.0f);
        vkCmdPushConstants(cmd, resolve.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, p);
        resolve.dispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    private static void writeResolveSet(long set, RenderTarget scene) {
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            VkDescriptorImageInfo.Buffer depth = VkDescriptorImageInfo.calloc(1, stack);
            depth.get(0).sampler(ComputeSets.nearestClampSampler()).imageView(scene.depthView())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(depth);
            VkDescriptorImageInfo.Buffer color = VkDescriptorImageInfo.calloc(1, stack);
            color.get(0).imageView(scene.colorView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(color);
            VkDescriptorBufferInfo.Buffer in = VkDescriptorBufferInfo.calloc(1, stack);
            in.get(0).buffer(Rr2Denoiser.inputBuffer()).offset(0).range(Rr2Denoiser.inputBytes());
            writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(in);
            VkDescriptorBufferInfo.Buffer out = VkDescriptorBufferInfo.calloc(1, stack);
            out.get(0).buffer(Rr2Denoiser.outputBuffer()).offset(Rr2Denoiser.outputOffset()).range(Rr2Denoiser.outputBytes());
            writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(out);
            vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);
        }
    }

    /** The constants of rt_composite.comp and rt_pt_resolve.comp (shared layout). */
    private static ByteBuffer compositeConstants(MemoryStack stack, float gi, boolean nrd, boolean ddgi) {
        float[] sun = RtLighting.sun();
        boolean sky = RtLighting.hasSky();
        boolean day = sun[1] >= 0.0f;
        float s = day ? 1.0f : -1.0f;
        float[] direct = sky ? RtLighting.directLight(sun) : new float[3];
        float[] skyMean = sky ? RtLighting.skyMean(sun) : new float[4];
        float skySource = sky ? RtLighting.skySource(sun) : 0.0f;
        double cx = CameraHistory.camX(), cy = CameraHistory.camY(), cz = CameraHistory.camZ();
        ByteBuffer p = stack.calloc(COMPOSITE_PUSH);
        invJittered.get(0, p);
        p.putFloat(64, Initializer.CONFIG.effectiveRtBlockLight()).putFloat(72, DEBUG)
                .putFloat(76, gi).putFloat(80, nrd ? 1.0f : 0.0f).putFloat(84, ddgi ? 1.0f : 0.0f);
        p.putFloat(96, sun[0]).putFloat(100, sun[1]).putFloat(104, sun[2]).putFloat(108, skySource);
        p.putFloat(112, direct[0]).putFloat(116, direct[1]).putFloat(120, direct[2]);
        p.putFloat(128, sun[0] * s).putFloat(132, sun[1] * s).putFloat(136, sun[2] * s)
                .putFloat(140, net.minecraft.client.Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16.0f);
        // Vanilla-lit pixels (entities, terrain past the RT range) to scene units: the physical
        // radiance of a white surface under the open sky against vanilla's daylight white.
        float physWhite = (0.2126f * direct[0] + 0.7152f * direct[1] + 0.0722f * direct[2]) * 0.5f / (float) Math.PI
                + (0.2126f * skyMean[0] + 0.7152f * skyMean[1] + 0.0722f * skyMean[2]);
        float vanillaWhite = sky ? 0.2f + 0.8f * sun[3] : 1.0f;
        p.putFloat(144, skyMean[0]).putFloat(148, skyMean[1]).putFloat(152, skyMean[2])
                .putFloat(156, sky ? physWhite / vanillaWhite : 1.0f);
        // Clouds (traced in the composite): the camera in the cloud pattern's space (vanilla's
        // drift of 0.03 blocks per tick along x, wrapped at the pattern's 3072 blocks), the
        // layer's bottom relative to the camera, on/off.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        boolean clouds = sky && mc.world != null && mc.gameSettings.shouldRenderClouds() != 0;
        if (clouds) {
            double drift = (mc.world.getTotalWorldTime() + mc.getRenderPartialTicks()) * 0.03;
            p.putFloat(160, (float) ((((cx + drift) % 3072.0) + 3072.0) % 3072.0))
                    .putFloat(164, (float) (mc.world.provider.getCloudHeight() - cy + 0.33))
                    .putFloat(168, (float) (((cz % 3072.0) + 3072.0) % 3072.0)).putFloat(172, 1.0f);
        }
        // Vanilla's terrain fog (baked into vanilla-lit pixels, removed by the composite): color
        // (display encoded), start and end distance.
        float[] fogColor = VRenderSystem.getShaderFogColor();
        float[] fog = VRenderSystem.terrainFog(mc.gameSettings.renderDistanceChunks * 16.0f);
        p.putFloat(176, fogColor[0]).putFloat(180, fogColor[1]).putFloat(184, fogColor[2]).putFloat(188, fog[0])
                .putFloat(192, fog[1]);
        return p;
    }

    /**
     * The constants of lighting.rgen / pathtrace.rgen / sharc_update.rgen (their shared push
     * layout; see lighting.rgen). Sets invJittered and prevRel.
     */
    private static ByteBuffer lightingConstants(MemoryStack stack, int w, int h, boolean hasPrev, float giRange,
                                                boolean nrd, boolean ddgi) {
        // The depth was rendered with the jittered projection.
        invJittered.identity()
                .m30(2.0f * CameraHistory.jitterX() / w)
                .m31(-2.0f * CameraHistory.jitterY() / h)
                .mul(CameraHistory.viewProj())
                .invert();
        double cx = CameraHistory.camX(), cy = CameraHistory.camY(), cz = CameraHistory.camZ();
        prevRel.set(hasPrev ? CameraHistory.prevViewProj() : CameraHistory.viewProj());
        if (hasPrev) {
            prevRel.translate((float) (cx - CameraHistory.prevCamX()), (float) (cy - CameraHistory.prevCamY()),
                    (float) (cz - CameraHistory.prevCamZ()));
        }

        float[] sun = RtLighting.sun();
        boolean sky = RtLighting.hasSky();
        boolean day = sun[1] >= 0.0f;
        // Physical light rig (RtLighting): the direct light's irradiance fades with the
        // atmosphere's transmittance itself, the shadow rays only need to know it shines.
        float sunStrength = sky ? 1.0f : 0.0f;
        float[] direct = sky ? RtLighting.directLight(sun) : new float[3];
        float[] skyMean = sky ? RtLighting.skyMean(sun) : new float[4];
        float skySource = sky ? RtLighting.skySource(sun) : 0.0f;
        float s = day ? 1.0f : -1.0f;
        float[] camOff = RtLights.cameraOffset(cx, cy, cz);

        ByteBuffer c = stack.calloc(RT_PUSH);
        invJittered.get(0, c);
        prevRel.get(64, c);
        c.putFloat(128, sun[0] * s).putFloat(132, sun[1] * s).putFloat(136, sun[2] * s).putFloat(140, sunStrength);
        c.putFloat(144, Initializer.CONFIG.effectiveRtShadowDistance() * 16.0f).putFloat(148, frame++ & 0xFFFF)
                .putFloat(152, hasPrev ? 1.0f : 0.0f).putFloat(156, RtLights.gridMask());
        c.putFloat(160, camOff[0]).putFloat(164, camOff[1]).putFloat(168, camOff[2]).putFloat(172, RtLights.count());
        c.putFloat(176, (float) (CameraHistory.prevCamX() - cx)).putFloat(180, (float) (CameraHistory.prevCamY() - cy))
                .putFloat(184, (float) (CameraHistory.prevCamZ() - cz))
                .putFloat(188, skySource);
        c.putFloat(192, skyMean[0]).putFloat(196, skyMean[1]).putFloat(200, skyMean[2]).putFloat(204, giRange);
        // Re-uploaded lights renumber: the previous reservoirs point at stale indices.
        boolean lightsStable = RtLights.generation() == lightGeneration;
        lightGeneration = RtLights.generation();
        c.putFloat(208, lightsStable ? 1.0f : 0.0f).putFloat(212, nrd ? 1.0f : 0.0f)
                .putFloat(216, RtLights.dynamicCount())
                // The checkerboard parity NRD expects this frame (BLACK mode, see lighting.rgen).
                .putFloat(220, nrd ? NrdDenoiser.nextFrameIndex() & 1 : 0.0f);
        // View-space z of a camera-relative position: the view matrix's third row.
        org.joml.Matrix4f view = CameraHistory.view();
        c.putFloat(224, view.m02()).putFloat(228, view.m12()).putFloat(232, view.m22()).putFloat(236, view.m32());
        c.putFloat(240, ddgi ? 1.0f : 0.0f).putFloat(244, direct[0]).putFloat(248, direct[1]).putFloat(252, direct[2]);
        return c;
    }

    private static void prepare() {
        if (rtPipeline == null) {
            rtPipeline = new RtPipeline(RtDescriptors.layout(), RT_PUSH, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/lighting.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit"),
                            new RtPipeline.HitGroup(null, "rt/shadow.rahit")});
            Initializer.LOGGER.info("[rt] lighting ready");
        }
        if (ptPipeline == null && pathTracingWanted()) {
            ptPipeline = new RtPipeline(RtDescriptors.layout(), RT_PUSH, VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    "rt/pathtrace.rgen", new String[]{"rt/debug.rmiss", "rt/shadow.rmiss"},
                    new RtPipeline.HitGroup[]{new RtPipeline.HitGroup("rt/debug.rchit", "rt/debug.rahit"),
                            new RtPipeline.HitGroup(null, "rt/shadow.rahit")});
            createResolveSets();
            ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile("rt_pt_resolve.comp",
                    ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
            resolve = new ComputePipeline(spv, new long[]{resolveLayout}, COMPOSITE_PUSH);
            spv.free();
            Initializer.LOGGER.info("[rt] path tracing ready (Ray Reconstruction)");
        }
        if (composite == null) {
            createSets();
            ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compileShaderFile("rt_composite.comp",
                    ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);
            composite = new ComputePipeline(spv, new long[]{setLayout}, COMPOSITE_PUSH);
            spv.free();
        }
    }

    private static void createSets() {
        VkDevice device = Vulkan.getDevice();
        int[] types = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
        try (MemoryStack stack = stackPush()) {
            LongBuffer ptr = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                sizes.get(i).type(types[i]).descriptorCount(RtDescriptors.SLOTS);
            }
            RtPipeline.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, ptr), "RT composite set layout");
            setLayout = ptr.get(0);
            RtPipeline.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RtDescriptors.SLOTS).pPoolSizes(sizes), null, ptr), "RT composite pool");
            pool = ptr.get(0);
            LongBuffer layouts = stack.mallocLong(RtDescriptors.SLOTS);
            for (int i = 0; i < RtDescriptors.SLOTS; i++) layouts.put(i, setLayout);
            LongBuffer out = stack.mallocLong(RtDescriptors.SLOTS);
            RtPipeline.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), out), "RT composite sets");
            for (int i = 0; i < RtDescriptors.SLOTS; i++) sets[i] = out.get(i);
        }
    }

    private static void createResolveSets() {
        VkDevice device = Vulkan.getDevice();
        int[] types = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
        try (MemoryStack stack = stackPush()) {
            LongBuffer ptr = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                sizes.get(i).type(types[i]).descriptorCount(RtDescriptors.SLOTS);
            }
            RtPipeline.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, ptr), "RT resolve set layout");
            resolveLayout = ptr.get(0);
            RtPipeline.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RtDescriptors.SLOTS).pPoolSizes(sizes), null, ptr), "RT resolve pool");
            resolvePool = ptr.get(0);
            LongBuffer layouts = stack.mallocLong(RtDescriptors.SLOTS);
            for (int i = 0; i < RtDescriptors.SLOTS; i++) layouts.put(i, resolveLayout);
            LongBuffer out = stack.mallocLong(RtDescriptors.SLOTS);
            RtPipeline.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(resolvePool).pSetLayouts(layouts), out), "RT resolve sets");
            for (int i = 0; i < RtDescriptors.SLOTS; i++) resolveSets[i] = out.get(i);
        }
    }

    private static void writeCompositeSet(long set, RenderTarget scene, StorageImage cur, StorageImage giCur,
                                          boolean nrd) {
        try (MemoryStack stack = stackPush()) {
            long[] views = {scene.depthView(), scene.colorView(), cur.view, aux.view, normals.view, giCur.view, aux2.view,
                    (nrd ? blockDenoised : unused16).view, (nrd ? giDenoised : unused16).view, albedo.view,
                    (nrd ? shadowOut : unused8).view};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(views.length, stack);
            for (int i = 0; i < views.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                if (i == 0) {
                    info.get(0).sampler(ComputeSets.nearestClampSampler()).imageView(views[i])
                            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                } else {
                    info.get(0).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                }
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).pImageInfo(info)
                        .descriptorType(i == 0 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            }
            vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);
        }
    }

    private static void memoryBarrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess,
                                      long dstStage, long dstAccess) {
        VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess);
        VkCompat.pipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    static void destroy() {
        NrdDenoiser.destroy();
        Rr2Denoiser.destroy();
        if (ptPipeline != null) { ptPipeline.destroy(); ptPipeline = null; }
        if (resolve != null) {
            resolve.cleanUp();
            resolve = null;
            vkDestroyDescriptorPool(Vulkan.getDevice(), resolvePool, null);
            vkDestroyDescriptorSetLayout(Vulkan.getDevice(), resolveLayout, null);
            resolvePool = resolveLayout = 0L;
        }
        RtDdgi.destroy();
        RtSharc.destroy();
        for (StorageImage img : new StorageImage[]{history[0], history[1], aux, normals, giHistory[0], giHistory[1], aux2,
                reservoirs[0], reservoirs[1], blockRaw, giRaw, viewZ, blockDenoised, giDenoised, albedo,
                penumbra, translucency, shadowOut, unused16, unused32, unused8, unusedR16}) {
            img.destroy();
        }
        historyValid = false;
        if (rtPipeline != null) { rtPipeline.destroy(); rtPipeline = null; }
        if (composite != null) {
            composite.cleanUp();
            composite = null;
            vkDestroyDescriptorPool(Vulkan.getDevice(), pool, null);
            vkDestroyDescriptorSetLayout(Vulkan.getDevice(), setLayout, null);
            pool = setLayout = 0L;
        }
    }
}
