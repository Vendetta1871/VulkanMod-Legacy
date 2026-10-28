package net.vulkanmod.vulkan;

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
     * LDR for now: a float target would skip the [0,1] clamp vanilla blending relies on.
     * The scene moves to RGBA16F with a tonemapping step once HDR lighting exists.
     */
    public static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    public enum Phase { NONE, SCENE, DISPLAY }

    public static final RenderTarget scene = new RenderTarget("scene");
    public static final RenderTarget display = new RenderTarget("display");

    private static int depthFormat;
    private static boolean depthHasStencil;
    private static float renderScale = 1.0f;
    private static Phase phase = Phase.NONE;
    private static int generation;
    /**
     * auto | fsr3 | fsr1 | blit (Config.upscaler, VULKANMOD_UPSCALER overrides).
     * auto: FSR 3 when the scene is scaled and the native library loads, else FSR 1.
     * "fsr3" also runs at scale 1 (native AA).
     */
    private static String upscaler = "auto";
    private static boolean blitOnly;
    private static boolean temporal;

    private FrameTargets() {}

    public static int depthFormat() { return depthFormat; }
    public static Phase phase() { return phase; }
    public static float renderScale() { return renderScale; }
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
        net.vulkanmod.config.Config config = net.vulkanmod.Initializer.CONFIG;
        upscaler = config.effectiveUpscaler();
        blitOnly = upscaler.equals("blit");
        float scale = config.effectiveRenderScale();
        if (scale != renderScale) {
            renderScale = scale;
            if (display.width() > 0) Drawer.shouldRecreate = true;
        }
        if (Vulkan.getDevice() != null) {
            net.vulkanmod.vulkan.rt.RtScene.applySettings();
        }
    }

    /** Recreates the targets at the current extent (settings changed their usage); waits for idle. */
    public static void recreateIfCreated() {
        if (display.width() <= 0) return;
        vkDeviceWaitIdle(Vulkan.getDevice());
        resize(display.width(), display.height());
    }

    /** Destroys the RT passes' pipelines and descriptors (RT turned off; device idle). */
    public static void releaseRtPasses() {
        RtDebugView.destroy();
        RtLightingPass.destroy();
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
        scene.create(sw, sh, COLOR_FORMAT,
                // STORAGE: RT passes modify the scene color in place.
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                        | (net.vulkanmod.vulkan.rt.RtScene.enabled() ? VK_IMAGE_USAGE_STORAGE_BIT : 0), depthFormat);
        // STORAGE: the upscalers write the display color from compute.
        display.create(displayW, displayH, COLOR_FORMAT,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | VK_IMAGE_USAGE_STORAGE_BIT,
                depthFormat);
        generation++;
    }

    static void destroy() {
        releaseRtPasses();
        if (net.vulkanmod.vulkan.rt.RtScene.enabled()) net.vulkanmod.vulkan.rt.RtScene.destroy();
        Fsr3Upscaler.destroy();
        Fsr1Upscaler.destroy();
        MotionVectorPass.destroy();
        opaqueColor.destroy();
        ComputeSets.destroySamplers();
        scene.destroy();
        display.destroy();
    }

    private static int depthAspect() {
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
        vkCmdEndRendering(cmd);
        if (shadows) {
            // T8: RT lighting lands on opaque geometry only; water and particles blend over it.
            recordRtShadows(cmd, stack);
            depthBackToAttachment(cmd, stack);
        }
        if (temporal) copyOpaque(cmd, stack);
        beginRendering(cmd, scene, VK_ATTACHMENT_LOAD_OP_LOAD, null, VK_ATTACHMENT_LOAD_OP_LOAD, stack);
    }

    private static boolean opaqueDone, rtRecorded;

    private static boolean rtShadowsWanted() {
        return net.vulkanmod.vulkan.rt.RtScene.enabled() && !RtDebugView.ACTIVE;
    }

    private static void recordRtShadows(VkCommandBuffer cmd, MemoryStack stack) {
        recordRtScene(cmd, stack);
        RtLightingPass.record(cmd, stack);
    }

    /** Builds this frame's TLAS once, outside rendering. */
    private static void recordRtScene(VkCommandBuffer cmd, MemoryStack stack) {
        if (rtRecorded) return;
        rtRecorded = true;
        net.vulkanmod.vulkan.rt.RtScene.record(cmd, stack);
    }

    /** Returns the scene depth to the attachment layout, contents kept, for the rest of the scene. */
    private static void depthBackToAttachment(VkCommandBuffer cmd, MemoryStack stack) {
        if (!depthReadable) return;
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
        imageBarrier(b.get(0), scene.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        pipelineBarrier(cmd, b, stack);
        depthReadable = false;
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

        copyOrBlit(cmd, scene.colorImage(), scene.width(), scene.height(), COLOR_FORMAT,
                opaqueColor.image, opaqueColor.width, opaqueColor.height, COLOR_FORMAT, stack);

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
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
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
        boolean requested = upscaler.equals("fsr3") || (upscaler.equals("auto") && scaled());
        return requested && Fsr3Upscaler.available();
    }

    /** Starts the SCENE phase: clears and begins rendering into the scene target. */
    static void beginFrame(VkCommandBuffer cmd, float[] clearColor, MemoryStack stack) {
        temporal = wantTemporal();
        updateLodBias();
        net.vulkanmod.render.CameraHistory.setJitterEnabled(temporal);
        MotionVectorPass.setEnabled(temporal);

        // Contents of the previous frame are discarded (UNDEFINED). The source stages
        // cover the previous frame's upscale read (blit or FSR compute) and depth writes.
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
        imageBarrier(barriers.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        depthToAttachment(barriers.get(1), scene);
        pipelineBarrier(cmd, barriers, stack);

        opaqueTaken = opaqueDone = rtRecorded = false;
        net.vulkanmod.vulkan.rt.RtEntities.beginFrame();
        depthReadable = false;
        if (net.vulkanmod.vulkan.rt.RtScene.enabled()) net.vulkanmod.vulkan.rt.RtScene.breadcrumb(cmd, stack, 0);
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
        vkCmdEndRendering(cmd);

        if (MotionVectorPass.enabled()) {
            MotionVectorPass.record(cmd, depthAspect(), stack);
        }

        if (net.vulkanmod.vulkan.rt.RtScene.enabled()) {
            // Frames without particles or translucent geometry get the shadows here.
            if (rtShadowsWanted() && !opaqueDone) recordRtShadows(cmd, stack);
            else recordRtScene(cmd, stack);
            net.vulkanmod.vulkan.rt.RtScene.breadcrumb(cmd, stack, 4);
        }

        if (RtDebugView.ACTIVE && net.vulkanmod.vulkan.rt.RtScene.enabled() && RtDebugView.record(cmd, stack)) {
            StorageImage rt = RtDebugView.output;
            blitIntoDisplay(cmd, rt.image, rt.width, rt.height, rt.format,
                    VK_IMAGE_LAYOUT_GENERAL, org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, stack);
            VkImageMemoryBarrier2.Buffer depth = VkImageMemoryBarrier2.calloc(1, stack);
            depthToAttachment(depth.get(0), display);
            pipelineBarrier(cmd, depth, stack);
        } else if (MotionVectorPass.DEBUG_VIEW) {
            StorageImage mv = MotionVectorPass.motionVectors;
            blitIntoDisplay(cmd, mv.image, mv.width, mv.height, mv.format,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, stack);
        } else if (temporal && Fsr3Upscaler.record(cmd, stack)) {
            // F5: FSR 3 upscale; leaves the display color ready as an attachment.
            VkImageMemoryBarrier2.Buffer depth = VkImageMemoryBarrier2.calloc(1, stack);
            depthToAttachment(depth.get(0), display);
            pipelineBarrier(cmd, depth, stack);
        } else if (!blitOnly && scaled()) {
            // F0: FSR 1 upscale; leaves the display color ready as an attachment.
            Fsr1Upscaler.record(cmd, stack);
            VkImageMemoryBarrier2.Buffer depth = VkImageMemoryBarrier2.calloc(1, stack);
            depthToAttachment(depth.get(0), display);
            pipelineBarrier(cmd, depth, stack);
        } else {
            blitIntoDisplay(cmd, scene.colorImage(), scene.width(), scene.height(), COLOR_FORMAT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, stack);
        }

        beginRendering(cmd, display, VK_ATTACHMENT_LOAD_OP_LOAD, null, VK_ATTACHMENT_LOAD_OP_CLEAR, stack);
        phase = Phase.DISPLAY;
    }

    /** Copies or linearly blits {@code src} into the display color and readies it as an attachment. */
    private static void blitIntoDisplay(VkCommandBuffer cmd, long src, int srcW, int srcH, int srcFormat,
                                        int srcLayout, long srcStage, long srcAccess, MemoryStack stack) {
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
                display.colorImage(), display.width(), display.height(), COLOR_FORMAT, stack);

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
        vkCmdEndRendering(cmd);
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
                swapchainImage, swapW, swapH, swapchainFormat, stack);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        imageBarrier(post.get(0), swapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_NONE, 0,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        pipelineBarrier(cmd, post, stack);
        if (net.vulkanmod.vulkan.rt.RtScene.enabled()) net.vulkanmod.vulkan.rt.RtScene.breadcrumb(cmd, stack, 5);
    }

    private static void beginRendering(VkCommandBuffer cmd, RenderTarget target, int colorLoadOp,
                                       float[] clearColor, int depthLoadOp, MemoryStack stack) {
        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
        color.get(0).sType$Default()
                .imageView(target.colorView())
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(colorLoadOp)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        if (clearColor != null) {
            color.get(0).clearValue().color().float32(0, clearColor[0]).float32(1, clearColor[1])
                    .float32(2, clearColor[2]).float32(3, clearColor[3]);
        }

        VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(target.depthView())
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(depthLoadOp)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        depth.clearValue().depthStencil().set(1.0f, 0);

        // Stencil is never attached: pipelines declare stencilAttachmentFormat UNDEFINED.
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1)
                .pColorAttachments(color)
                .pDepthAttachment(depth);
        info.renderArea().offset().set(0, 0);
        info.renderArea().extent().set(target.width(), target.height());

        vkCmdBeginRendering(cmd, info);

        // Viewport and scissor follow the target; the viewport flips Y like GL.
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.x(0.0f).y(target.height()).width(target.width()).height(-target.height())
                .minDepth(0.0f).maxDepth(1.0f);
        vkCmdSetViewport(cmd, 0, viewport);

        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.extent().set(target.width(), target.height());
        vkCmdSetScissor(cmd, 0, scissor);
    }

    private static void depthToAttachment(VkImageMemoryBarrier2 b, RenderTarget target) {
        // Source stages also cover the previous frame's compute and RT reads.
        imageBarrier(b, target.depthImage(), depthAspect(),
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT
                        | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }

    /** Raw copy when format and extent match, otherwise a linear blit (converts and scales). */
    private static void copyOrBlit(VkCommandBuffer cmd, long src, int srcW, int srcH, int srcFormat,
                                   long dst, int dstW, int dstH, int dstFormat, MemoryStack stack) {
        if (srcFormat == dstFormat && srcW == dstW && srcH == dstH) {
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
            region.dstOffsets(1).set(dstW, dstH, 1);
            vkCmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_LINEAR);
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
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
    }
}
