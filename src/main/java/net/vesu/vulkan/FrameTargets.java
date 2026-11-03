package net.vesu.vulkan;

import net.vesu.Env;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * The frame's offscreen targets and phases (B2/B3).
 * <ol>
 *   <li>SCENE: the world renders into {@link #scene} at render resolution.</li>
 *   <li>The scene is upscaled into {@link #display} (a linear blit for now; FSR later).</li>
 *   <li>DISPLAY: the hand and the GUI render into {@link #display} at display resolution,
 *       on top of the upscaled image, so vanilla blending (e.g. the inverting crosshair)
 *       still sees the world. Frame generation gets the HUD-less image from step 2.</li>
 *   <li>The display color is copied into the acquired swapchain image.</li>
 * </ol>
 * One set is shared by all frames in flight; the queue runs frames in order and the
 * barriers' source scopes cover the previous frame's use.
 */
public final class FrameTargets {

    /**
     * The display and, by default, the scene: a float target would skip the [0,1] clamp
     * vanilla blending relies on, so only a pack that tonemaps opts out (see {@link #sceneFormat}).
     */
    public static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    /** Scene color of a pack with {@code renderer.hdr}; its combination writes display-referred values. */
    public static final int HDR_SCENE_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

    public enum Phase { NONE, SCENE, DISPLAY }

    public static final RenderTarget scene = new RenderTarget("scene");
    public static final RenderTarget display = new RenderTarget("display");

    private static int depthFormat;
    private static boolean depthHasStencil;
    private static float renderScale = 1.0f;
    private static Phase phase = Phase.NONE;
    private static int generation;
    /**
     * auto | fsr3 | fsr1 | blit (Config.upscaler, VESU_UPSCALER overrides).
     * auto: FSR 3 when the scene is scaled, ray tracing is on and the native library loads,
     * else FSR 1.
     * "fsr3" also runs at scale 1 (native AA).
     */
    private static String upscaler = "auto";
    private static boolean blitOnly;
    private static boolean temporal;

    /**
     * Debug knob for the shader-pack coordinate convention (docs/shaderpacks, D4):
     * VESU_PACK_SPACE=1 renders the whole SCENE phase in pack space - positive
     * viewport, so image row 0 is the bottom row as in GL, and clockwise front faces - and
     * flips once when the scene is copied into the display. The picture must not change.
     * Forces the blit upscaler; ignored while ray tracing is on (its passes read the scene
     * in Vulkan row order).
     */
    private static final boolean PACK_SPACE_DEBUG = Env.get("VESU_PACK_SPACE") != null;
    /** The current frame's SCENE phase renders in pack space. */
    private static boolean packSpaceFrame;
    private static boolean packSpaceRtWarned;
    /** The shader pack run this frame (docs/shaderpacks, M1), or null: the only per-frame pack check. */
    private static PackRuntime pack;
    /** {@link #pack} has geometry programs: the scene phase attaches their targets. */
    private static boolean geometry;
    private static boolean translucentEntered;
    /** Format of {@link #scene}: {@link #COLOR_FORMAT} or, for the pack run this frame, {@link #HDR_SCENE_FORMAT}. */
    private static int sceneFormat = COLOR_FORMAT;

    /** True when this frame's scene is in GL row order (a pack runs, or the debug knob). */
    static boolean packSpaceFrame() { return packSpaceFrame; }

    private FrameTargets() {}

    public static int depthFormat() { return depthFormat; }
    public static Phase phase() { return phase; }

    /**
     * True while draws go to the scene in pack space (GL row order): the viewport is
     * positive and pipelines use a clockwise front face (Pipeline.packStateKey bit 37).
     */
    public static boolean packSpace() { return packSpaceFrame && phase == Phase.SCENE; }

    /** The pack whose geometry programs the next draw goes through, or null. */
    static PackRuntime geometry() {
        return geometry && phase == Phase.SCENE ? pack : null;
    }

    /** The pack run this frame, or null. */
    static PackRuntime packRuntime() {
        return pack;
    }

    /** With a pack the hand stays in the scene, so the post passes see it. */
    public static boolean handInScene() {
        return pack != null && packSpaceFrame && phase == Phase.SCENE;
    }

    /** VESU_FORWARD_Z=1: forward depth everywhere, for A/B comparisons of the reverse-Z path. */
    private static final boolean FORWARD_Z = net.vesu.Env.get("VESU_FORWARD_Z") != null;

    /** True once the world's depth must survive a depth clear (the one vanilla issues for the hand). */
    static boolean keepsDepth() {
        return pack != null && packSpaceFrame && phase == Phase.SCENE && translucentEntered;
    }

    /** True while draws go to an RGBA16F scene (Pipeline.packStateKey bit 38). */
    public static boolean hdrScene() { return sceneFormat == HDR_SCENE_FORMAT && phase == Phase.SCENE; }
    public static float renderScale() { return renderScale; }
    private static long recording;

    /** Bumped at the start of every recorded frame (also frames that never get submitted). */
    static long recordingId() { return recording; }

    /** Bumped whenever the targets are recreated; descriptor users compare it. */
    public static int generation() { return generation; }

    /** Extent of the target currently rendered to (display extent outside a frame). */
    public static int currentWidth() { return phase == Phase.SCENE ? scene.width() : display.width(); }
    public static int currentHeight() { return phase == Phase.SCENE ? scene.height() : display.height(); }

    /**
     * Re-reads the upscaling settings (F7). A changed render scale recreates the targets
     * with the next swapchain recreation, which runs at the start of the next frame.
     */
    public static void applySettings() {
        net.vesu.config.Config config = net.vesu.Initializer.CONFIG;
        upscaler = PACK_SPACE_DEBUG ? "blit" : config.effectiveUpscaler();
        float scale = config.effectiveRenderScale();
        // Above 1 the world is supersampled: FSR only upscales, a linear blit filters it down.
        blitOnly = upscaler.equals("blit") || scale > 1.0f;
        boolean share = scale == 1.0f && shareColorAllowed();
        if (scale != renderScale || (display.width() > 0 && share != sharedColor)) {
            renderScale = scale;
            if (display.width() > 0) Drawer.shouldRecreate = true;
        }
        if (Vulkan.getDevice() != null) {
            net.vesu.vulkan.rt.RtScene.applySettings();
            // F9: switching frame generation swaps the swapchain.
            if (display.width() > 0 && FrameGeneration.active() != FrameGeneration.wanted()) Drawer.shouldRecreate = true;
        }
    }

    /** Recreates the targets at the current extent (settings changed their usage); waits for idle. */
    public static void recreateIfCreated() {
        if (display.width() <= 0) return;
        Vulkan.waitIdle();
        resize(display.width(), display.height());
    }

    /** Destroys the RT passes' pipelines and descriptors (RT turned off; device idle). */
    public static void releaseRtPasses() {
        RtDebugView.destroy();
        RtLightingPass.destroy();
        RtReflectionPass.destroy();
        RtDescriptors.destroy();
    }

    static void init(int depthFormat) {
        applySettings();
        FrameTargets.depthFormat = depthFormat;
        depthHasStencil = depthFormat == VK_FORMAT_D32_SFLOAT_S8_UINT || depthFormat == VK_FORMAT_D24_UNORM_S8_UINT;
    }

    /** (Re)creates both targets for a display extent; the GPU must be idle. */
    static void resize(int displayW, int displayH) {
        int sw = Math.max(1, Math.round(displayW * renderScale));
        int sh = Math.max(1, Math.round(displayH * renderScale));
        // The scene goes first: it may borrow the display color, which must outlive it.
        scene.destroy();
        // STORAGE: the upscalers write the display color from compute (and RT, when shared).
        display.create(displayW, displayH, COLOR_FORMAT,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | VK_IMAGE_USAGE_STORAGE_BIT,
                depthFormat);
        sharedColor = sw == displayW && sh == displayH && shareColorAllowed();
        scene.create(sw, sh, sceneFormat,
                // STORAGE: RT passes modify the scene color in place.
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                        | (net.vesu.vulkan.rt.RtScene.enabled() ? VK_IMAGE_USAGE_STORAGE_BIT : 0), depthFormat,
                sharedColor ? display : null);
        generation++;
    }

    /**
     * N2: at render scale 1 without a temporal upscaler the scene renders straight into the
     * display color (no full-screen copy between the phases). FSR 3 at scale 1 needs a
     * separate input; the debug views blit over the display.
     */
    private static boolean sharedColor;

    private static boolean shareColorAllowed() {
        // Pack space needs the flipping copy into the display; an HDR scene has another format.
        return !upscaler.equals("fsr3") && !RtDebugView.ACTIVE && !MotionVectorPass.DEBUG_VIEW && !PACK_SPACE_DEBUG
                && sceneFormat == COLOR_FORMAT;
    }

    static void destroy() {
        GpuTimer.destroy();
        PackRuntime.destroy();
        pack = null;
        sceneFormat = COLOR_FORMAT;
        releaseRtPasses();
        if (net.vesu.vulkan.rt.RtScene.enabled()) net.vesu.vulkan.rt.RtScene.destroy();
        Fsr3Upscaler.destroy();
        Fsr1Upscaler.destroy();
        MotionVectorPass.destroy();
        opaqueColor.destroy();
        ComputeSets.destroySamplers();
        scene.destroy();
        display.destroy();
    }

    static int depthAspect() {
        return depthHasStencil ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
    }

    /** Scene color before particles and translucent geometry (F2, auto-reactive input). */
    static final StorageImage opaqueColor = new StorageImage(COLOR_FORMAT,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
    private static boolean opaqueTaken;

    /** True when this frame's opaque-only copy exists. */
    static boolean opaqueTaken() { return opaqueTaken; }

    /**
     * Copies the scene color before the first particle or translucent draw (F2). FSR's
     * auto-reactive pass compares it with the final scene color. Only for the temporal
     * upscaler; once per frame. Rendering resumes with color and depth loaded.
     */
    public static void snapshotOpaque(VkCommandBuffer cmd, MemoryStack stack) {
        if (phase != Phase.SCENE || opaqueDone) return;
        boolean shadows = rtShadowsWanted();
        if (!temporal && !shadows) return;
        opaqueDone = true;
        VkCompat.endRendering(cmd);
        if (shadows) {
            // T8: RT lighting lands on opaque geometry only; water and particles blend over it.
            recordRtShadows(cmd, stack);
            depthBackToAttachment(cmd, stack);
        }
        // The opaque-only copy feeds FSR's reactive mask and the RT water refraction.
        copyOpaque(cmd, stack);
        beginRendering(cmd, scene, VK_ATTACHMENT_LOAD_OP_LOAD, null, VK_ATTACHMENT_LOAD_OP_LOAD, stack);
    }

    private static boolean opaqueDone, rtRecorded;

    private static boolean rtShadowsWanted() {
        return net.vesu.vulkan.rt.RtScene.enabled() && !RtDebugView.ACTIVE;
    }

    private static void recordRtShadows(VkCommandBuffer cmd, MemoryStack stack) {
        recordRtScene(cmd, stack);
        RtLightingPass.record(cmd, stack);
        GpuTimer.mark(cmd, GpuTimer.RT_LIGHTING);
        RtReflectionPass.record(cmd, stack, false);
        GpuTimer.mark(cmd, GpuTimer.RT_REFLECT_OPAQUE);
    }

    /** Builds this frame's TLAS once, outside rendering. */
    private static void recordRtScene(VkCommandBuffer cmd, MemoryStack stack) {
        if (rtRecorded) return;
        rtRecorded = true;
        net.vesu.vulkan.rt.RtScene.record(cmd, stack);
        GpuTimer.mark(cmd, GpuTimer.RT_SCENE);
    }

    /** Returns the scene depth to the attachment layout, contents kept, for the rest of the scene. */
    private static void depthBackToAttachment(VkCommandBuffer cmd, MemoryStack stack) {
        if (!depthReadable) return;
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
        imageBarrier(b.get(0), scene.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                        | rtStage(),
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        pipelineBarrier(cmd, b, stack);
        depthReadable = false;
    }

    /**
     * Before the translucent layer of a pack frame: leaves rendering when the pack has a
     * reason to (depth copy, {@code DEFERRED}), and resumes with everything loaded.
     */
    static void enterTranslucent(VkCommandBuffer cmd, MemoryStack stack) {
        if (phase != Phase.SCENE || pack == null || translucentEntered) return;
        translucentEntered = true;
        pack.shadowTranslucent(stack);
        if (!pack.usesTranslucentHook()) return;
        VkCompat.endRendering(cmd);
        pack.recordDeferred(cmd, stack);
        depthBackToAttachment(cmd, stack);
        Drawer.invalidateBoundPipeline(cmd);
        pack.resumeGeometry(cmd, stack);
        beginRendering(cmd, scene, VK_ATTACHMENT_LOAD_OP_LOAD, null, VK_ATTACHMENT_LOAD_OP_LOAD, stack);
    }

    /** Before the hand: the copy behind depthtex2 leaves rendering and comes back with everything loaded. */
    static void beforeHand(VkCommandBuffer cmd, MemoryStack stack) {
        if (phase != Phase.SCENE || pack == null || !pack.wantsHandDepth()) return;
        VkCompat.endRendering(cmd);
        pack.copyHandDepth(cmd, stack);
        Drawer.invalidateBoundPipeline(cmd);
        beginRendering(cmd, scene, VK_ATTACHMENT_LOAD_OP_LOAD, null, VK_ATTACHMENT_LOAD_OP_LOAD, stack);
    }

    /**
     * OptiFine scales the hand's projection z by MC_HAND_DEPTH, which puts the hand in
     * [0.4375, 0.5625] of the depth range. Packs test for that range and undo it to rebuild
     * the hand's position, so the numbers matter and not only that it draws in front.
     */
    static void setHandDepth(VkCommandBuffer cmd, boolean hand, MemoryStack stack) {
        if (!handInScene()) return;
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.x(0.0f).y(0.0f).width(scene.width()).height(scene.height());
        float half = pack.handDepth() * 0.5f;
        viewport.minDepth(hand ? 0.5f - half : 0.0f).maxDepth(hand ? 0.5f + half : 1.0f);
        vkCmdSetViewport(cmd, 0, viewport);
    }

    /** Copies the scene depth into {@code dst} (DEPTH format); the scene depth stays an attachment. */
    static void copySceneDepth(VkCommandBuffer cmd, long dst, MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(pre.get(0), scene.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        imageBarrier(pre.get(1), dst, depthAspect(),
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        pipelineBarrier(cmd, pre, stack);

        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().set(VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1);
        region.dstSubresource().set(VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1);
        region.extent().set(scene.width(), scene.height(), 1);
        vkCmdCopyImage(cmd, scene.depthImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(post.get(0), scene.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        imageBarrier(post.get(1), dst, depthAspect(),
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        pipelineBarrier(cmd, post, stack);
    }

    /** The depth texel at the screen center into {@code buffer}, as a float or a packed D24. */
    static void copyCenterDepth(VkCommandBuffer cmd, long buffer, MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(1, stack);
        imageBarrier(pre.get(0), scene.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        pipelineBarrier(cmd, pre, stack);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.imageSubresource().set(VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1);
        region.imageOffset().set(scene.width() / 2, scene.height() / 2, 0);
        region.imageExtent().set(1, 1, 1);
        vkCmdCopyImageToBuffer(cmd, scene.depthImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        imageBarrier(post.get(0), scene.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        pipelineBarrier(cmd, post, stack);
    }

    private static void copyOpaque(VkCommandBuffer cmd, MemoryStack stack) {
        opaqueColor.ensure(scene.width(), scene.height());

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        // The previous frame's reactive pass read the copy.
        imageBarrier(pre.get(1), opaqueColor.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        pipelineBarrier(cmd, pre, stack);

        copyOrBlit(cmd, scene.colorImage(), scene.width(), scene.height(), sceneFormat,
                opaqueColor.image, opaqueColor.width, opaqueColor.height, COLOR_FORMAT, false, stack);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(post.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        imageBarrier(post.get(1), opaqueColor.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        pipelineBarrier(cmd, post, stack);
        opaqueTaken = true;
    }

    private static boolean depthReadable;

    /**
     * Moves the scene depth from attachment to SHADER_READ_ONLY_OPTIMAL once per frame for
     * the compute and ray-tracing passes after the scene phase (ffx-api expects that layout).
     */
    static void sceneDepthToShaderRead(VkCommandBuffer cmd, MemoryStack stack) {
        if (depthReadable) return;
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
        imageBarrier(b.get(0), scene.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                        | rtStage(),
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        pipelineBarrier(cmd, b, stack);
        depthReadable = true;
    }

    private static boolean scaled() {
        return scene.width() != display.width() || scene.height() != display.height();
    }

    /**
     * Mip LOD bias for textures sampled now (F6): only in the scene phase and only when
     * the scene renders below display resolution. AMD: log2(render/display), minus one
     * more for the temporal upscaler, which recovers detail from jittered samples.
     */
    public static float textureLodBias() {
        if (phase != Phase.SCENE || !lodBiasActive) return 0.0f;
        return lodBias;
    }

    private static boolean lodBiasActive;
    private static float lodBias;

    private static void updateLodBias() {
        float ratio = (float) scene.width() / display.width();
        float bias = (float) (Math.log(ratio) / Math.log(2.0)) - (temporal ? 1.0f : 0.0f);
        // Quantized: every distinct value creates a sampler variant.
        lodBias = Math.round(bias * 8.0f) / 8.0f;
        lodBiasActive = !blitOnly && lodBias != 0.0f;
    }

    /** Whether this frame uses the temporal upscaler (decided at frame start: jitter depends on it). */
    private static boolean wantTemporal() {
        // auto: FSR 3 only with ray tracing, whose noisy lighting gains from the temporal
        // accumulation; without RT the scene is cheap and FSR 1 costs less (AUDIT N1).
        boolean requested = upscaler.equals("fsr3")
                || (upscaler.equals("auto") && scaled() && net.vesu.vulkan.rt.RtScene.enabled());
        // Camera history (jitter, motion vectors) is captured by the batched chunk path only.
        return requested && !blitOnly && net.vesu.render.TerrainRenderer.BATCH && Fsr3Upscaler.available();
    }

    /** Starts the SCENE phase: clears and begins rendering into the scene target. */
    static void beginFrame(VkCommandBuffer cmd, float[] clearColor, MemoryStack stack) {
        recording++;
        GpuTimer.beginFrame(cmd);
        pack = PackRuntime.forFrame();
        // Packs keep forward depth until their programs are rewritten for reverse-Z (AUDIT 5.16).
        VRenderSystem.reverseZ = pack == null && !FORWARD_Z;
        net.vesu.render.ChunkBatcher.requestVertexFlags(pack != null ? pack.vertexFlags() : 0);
        int wantedFormat = pack != null && pack.hdrScene() ? HDR_SCENE_FORMAT : COLOR_FORMAT;
        if (wantedFormat != sceneFormat) {
            // Only when the pack changes: nothing of this frame touched the targets yet.
            sceneFormat = wantedFormat;
            recreateIfCreated();
        }
        // A pack replaces the temporal upscaler: FSR 3 reads the scene in Vulkan row order.
        boolean rtPack = pack != null && net.vesu.vulkan.rt.RtScene.enabled();
        temporal = (pack == null || rtPack) && wantTemporal();
        updateLodBias();
        net.vesu.render.CameraHistory.setJitterEnabled(temporal);
        MotionVectorPass.setEnabled(temporal || (pack != null && pack.needsMotion()));

        // Contents of the previous frame are discarded (UNDEFINED). The source stages
        // cover the previous frame's upscale read (blit or FSR compute) and depth writes.
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(barriers.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        depthToAttachment(barriers.get(1), scene);
        pipelineBarrier(cmd, barriers, stack);

        opaqueTaken = opaqueDone = rtRecorded = false;
        net.vesu.vulkan.rt.RtEntities.beginFrame();
        depthReadable = false;
        net.vesu.vulkan.rt.RtScene.breadcrumb(cmd, stack, 0);
        // RT keeps Vulkan row order: a pack on top of it samples through vkm_hostUV.
        packSpaceFrame = (pack != null || PACK_SPACE_DEBUG) && !net.vesu.vulkan.rt.RtScene.enabled();
        if (PACK_SPACE_DEBUG && !packSpaceFrame && !packSpaceRtWarned) {
            packSpaceRtWarned = true;
            net.vesu.Initializer.LOGGER.warn("[frame] VESU_PACK_SPACE is ignored while ray tracing is on");
        }
        geometry = pack != null && pack.hasGeometry();
        translucentEntered = false;
        if (pack != null) {
            pack.beginShadow(stack);
            pack.recordPrologue(cmd, stack);
        }
        if (geometry) pack.beginGeometry(cmd, stack);
        beginRendering(cmd, scene, VK_ATTACHMENT_LOAD_OP_CLEAR, clearColor, VK_ATTACHMENT_LOAD_OP_CLEAR, stack);
        phase = Phase.SCENE;
    }

    /**
     * Leaves the SCENE phase: upscales the scene into the display target and begins
     * rendering there (color kept, depth cleared - vanilla clears depth before both the
     * hand and the GUI). No-op unless the scene phase is active.
     */
    public static void enterDisplayPhase(VkCommandBuffer cmd, MemoryStack stack) {
        if (phase != Phase.SCENE) return;
        VkCompat.endRendering(cmd);
        GpuTimer.mark(cmd, GpuTimer.SCENE_END);

        if (MotionVectorPass.enabled()) {
            MotionVectorPass.record(cmd, depthAspect(), stack);
        }

        if (net.vesu.vulkan.rt.RtScene.enabled()) {
            // Frames without particles or translucent geometry get the shadows here.
            if (rtShadowsWanted() && !opaqueDone) recordRtShadows(cmd, stack);
            else recordRtScene(cmd, stack);
            // Reflections see the translucent layer, so they run after it.
            if (rtShadowsWanted()) RtReflectionPass.record(cmd, stack, true);
            GpuTimer.mark(cmd, GpuTimer.RT_REFLECT_TRANSLUCENT);
            net.vesu.vulkan.rt.RtScene.breadcrumb(cmd, stack, 4);
        }

        if (pack != null) {
            // POST_SCENE + combination; the combination writes the scene color back in
            // Vulkan row order, so nothing below flips it again.
            pack.record(cmd, stack);
            Drawer.invalidateBoundPipeline(cmd);
            GpuTimer.mark(cmd, GpuTimer.PACK);
        }

        if (RtDebugView.ACTIVE && net.vesu.vulkan.rt.RtScene.enabled() && RtDebugView.record(cmd, stack)) {
            StorageImage rt = RtDebugView.output;
            blitIntoDisplay(cmd, rt.image, rt.width, rt.height, rt.format,
                    VK_IMAGE_LAYOUT_GENERAL, org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, false, stack);
            VkImageMemoryBarrier2.Buffer depth = VkImageMemoryBarrier2.calloc(1, stack);
            depthToAttachment(depth.get(0), display);
            pipelineBarrier(cmd, depth, stack);
        } else if (MotionVectorPass.DEBUG_VIEW) {
            StorageImage mv = MotionVectorPass.motionVectors;
            blitIntoDisplay(cmd, mv.image, mv.width, mv.height, mv.format,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, false, stack);
        } else if (temporal && Fsr3Upscaler.record(cmd, stack)) {
            // F5: FSR 3 upscale; leaves the display color ready as an attachment.
            // F9: frame generation takes its HUD-less copy before the hand and the GUI.
            FrameGeneration.record(cmd, stack);
            VkImageMemoryBarrier2.Buffer depth = VkImageMemoryBarrier2.calloc(1, stack);
            depthToAttachment(depth.get(0), display);
            pipelineBarrier(cmd, depth, stack);
        } else if (!blitOnly && scaled()) {
            // F0: FSR 1 upscale; leaves the display color ready as an attachment.
            Fsr1Upscaler.record(cmd, stack);
            VkImageMemoryBarrier2.Buffer depth = VkImageMemoryBarrier2.calloc(1, stack);
            depthToAttachment(depth.get(0), display);
            pipelineBarrier(cmd, depth, stack);
        } else if (sharedColor) {
            // The scene already is the display color (still an attachment): only the
            // display depth needs to become an attachment.
            VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(2, stack);
            imageBarrier(b.get(0), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            depthToAttachment(b.get(1), display);
            pipelineBarrier(cmd, b, stack);
        } else {
            // A pack-space scene without a pack (the debug knob) is in GL row order: this
            // copy is the one flip (D4). A pack's combination already flipped it.
            blitIntoDisplay(cmd, scene.colorImage(), scene.width(), scene.height(), sceneFormat,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, packSpaceFrame && pack == null, stack);
        }

        net.vesu.vulkan.rt.RtScene.breadcrumb(cmd, stack, 6);
        GpuTimer.mark(cmd, GpuTimer.UPSCALE);
        beginRendering(cmd, display, VK_ATTACHMENT_LOAD_OP_LOAD, null, VK_ATTACHMENT_LOAD_OP_CLEAR, stack);
        phase = Phase.DISPLAY;
    }

    /** Copies or linearly blits {@code src} into the display color and readies it as an attachment. */
    private static void blitIntoDisplay(VkCommandBuffer cmd, long src, int srcW, int srcH, int srcFormat,
                                        int srcLayout, long srcStage, long srcAccess, boolean flipY,
                                        MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(pre.get(0), src, VK_IMAGE_ASPECT_COLOR_BIT,
                srcStage, srcAccess,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                srcLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        // The previous frame's swapchain copy read the display color.
        imageBarrier(pre.get(1), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        pipelineBarrier(cmd, pre, stack);

        copyOrBlit(cmd, src, srcW, srcH, srcFormat,
                display.colorImage(), display.width(), display.height(), COLOR_FORMAT, flipY, stack);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(post.get(0), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        depthToAttachment(post.get(1), display);
        pipelineBarrier(cmd, post, stack);
    }

    /**
     * Ends the frame: finishes the display phase and copies the display color into the
     * acquired swapchain image, leaving it in PRESENT_SRC. The submit waits for the
     * acquire at the TRANSFER stage, which the swapchain barrier's source scope chains onto.
     */
    static void endFrame(VkCommandBuffer cmd, long swapchainImage, int swapchainFormat,
                         int swapW, int swapH, MemoryStack stack) {
        // Frames without a hand or GUI (loading screens drawn outside updateCameraAndRender).
        enterDisplayPhase(cmd, stack);
        VkCompat.endRendering(cmd);
        phase = Phase.NONE;

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(pre.get(0), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        imageBarrier(pre.get(1), swapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        pipelineBarrier(cmd, pre, stack);

        copyOrBlit(cmd, display.colorImage(), display.width(), display.height(), COLOR_FORMAT,
                swapchainImage, swapW, swapH, swapchainFormat, false, stack);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        imageBarrier(post.get(0), swapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_NONE, 0,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        pipelineBarrier(cmd, post, stack);
        GpuTimer.mark(cmd, GpuTimer.FRAME_END);
        net.vesu.vulkan.rt.RtScene.breadcrumb(cmd, stack, 5);
    }

    private static void beginRendering(VkCommandBuffer cmd, RenderTarget target, int colorLoadOp,
                                       float[] clearColor, int depthLoadOp, MemoryStack stack) {
        PackRuntime extra = target == scene && geometry ? pack : null;
        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(
                extra != null ? extra.colorAttachments() : 1, stack);
        if (extra != null) extra.writeAttachments(color);
        if (extra == null || !extra.replacesSceneColor()) {
            color.get(0).sType$Default()
                    .imageView(target.colorView())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(colorLoadOp)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clearColor != null) {
                color.get(0).clearValue().color().float32(0, clearColor[0]).float32(1, clearColor[1])
                        .float32(2, clearColor[2]).float32(3, clearColor[3]);
            }
        }

        VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(target.depthView())
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(depthLoadOp)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        depth.clearValue().depthStencil().set(VRenderSystem.farDepth(), 0);

        // Stencil is never attached: pipelines declare stencilAttachmentFormat UNDEFINED.
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1)
                .pColorAttachments(color)
                .pDepthAttachment(depth);
        info.renderArea().offset().set(0, 0);
        info.renderArea().extent().set(target.width(), target.height());

        VkCompat.beginRendering(cmd, info);

        // Viewport and scissor follow the target; the viewport flips Y like GL, so images
        // are stored upright. In pack space the scene keeps GL row order instead (positive
        // viewport, row 0 at the bottom); the copy into the display flips it back.
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        if (target == scene && packSpaceFrame) {
            viewport.x(0.0f).y(0.0f).width(target.width()).height(target.height());
        } else {
            viewport.x(0.0f).y(target.height()).width(target.width()).height(-target.height());
        }
        viewport.minDepth(0.0f).maxDepth(1.0f);
        vkCmdSetViewport(cmd, 0, viewport);

        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.extent().set(target.width(), target.height());
        vkCmdSetScissor(cmd, 0, scissor);
    }

    private static void depthToAttachment(VkImageMemoryBarrier2 b, RenderTarget target) {
        // Source stages also cover the previous frame's compute and RT reads.
        imageBarrier(b, target.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT
                        | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                        | rtStage(),
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }

    /** The RT shader stage for barriers; zero on a device without the feature. */
    private static long rtStage() {
        DeviceInfo info = Vulkan.getDeviceInfo();
        return info != null && info.features != null && info.features.rayTracingPipeline
                ? org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR : 0L;
    }

    /** Raw copy when format and extent match, otherwise a linear blit (converts and scales). */
    /** {@code flipY}: mirror vertically (a blit with swapped destination rows). */
    private static void copyOrBlit(VkCommandBuffer cmd, long src, int srcW, int srcH, int srcFormat,
                                   long dst, int dstW, int dstH, int dstFormat, boolean flipY, MemoryStack stack) {
        boolean sameSize = srcW == dstW && srcH == dstH;
        if (srcFormat == dstFormat && sameSize && !flipY) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.extent().set(srcW, srcH, 1);
            vkCmdCopyImage(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        } else {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.srcOffsets(1).set(srcW, srcH, 1);
            if (flipY) {
                region.dstOffsets(0).set(0, dstH, 0);
                region.dstOffsets(1).set(dstW, 0, 1);
            } else {
                region.dstOffsets(1).set(dstW, dstH, 1);
            }
            // Same size: nearest keeps the flipped copy exact.
            vkCmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
        }
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
        VkCompat.pipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
    }
}
