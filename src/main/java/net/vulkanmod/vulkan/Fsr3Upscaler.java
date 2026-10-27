package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.CameraHistory;
import net.vulkanmod.vulkan.ffx.FfxApi;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static net.vulkanmod.vulkan.ffx.FfxApi.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * FSR 3.1 temporal upscaler through AMD's native ffx-api (F5). Inputs at render
 * resolution: jittered scene color (LDR, gamma), depth, camera motion vectors;
 * output: the display color. Falls back to FSR 1 when the library is missing.
 */
final class Fsr3Upscaler {

    /** ffxContext handle storage (a pointer-sized native slot). */
    private static long pContext;
    private static boolean contextValid;
    private static int contextGeneration = -1;
    private static boolean failed;
    private static boolean resetNext = true;
    private static long lastDispatchNanos;

    private static final float REACTIVE_SCALE = envFloat("VULKANMOD_FSR3_REACTIVE_SCALE", 3.0f);

    private static float envFloat(String name, float def) {
        try {
            String v = System.getenv(name);
            return v != null ? Float.parseFloat(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static final boolean DEBUG_VIEW = System.getenv("VULKANMOD_FSR3_DEBUG") != null;

    private static ByteBuffer dispatchDesc;
    private static ByteBuffer reactiveDesc;

    /**
     * F2: auto-generated reactive mask (render resolution). R32F although FSR documents R8:
     * the SDK 1.1.4 GLSL declares the auto-reactive output as r32f.
     */
    private static final StorageImage reactive = new StorageImage(VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);

    private Fsr3Upscaler() {}

    /** True when the native library is present and no context creation failed. */
    static boolean available() {
        return !failed && FfxApi.load();
    }

    /** Called when the camera jumped (teleport, dimension change, respawn). */
    static void requestReset() {
        resetNext = true;
    }

    private static boolean ensureContext() {
        if (contextValid && contextGeneration == FrameTargets.generation()) return true;
        // Resize waits for the device to idle before bumping the generation.
        releaseContext();

        if (pContext == NULL) pContext = nmemCalloc(1, 8);
        ByteBuffer backend = memCalloc(BACKEND_VK_SIZE);
        ByteBuffer create = memCalloc(CREATE_UPSCALE_SIZE);
        try {
            VkDevice device = Vulkan.getDevice();
            header(backend, 0, CREATE_CONTEXT_DESC_TYPE_BACKEND_VK, NULL);
            backend.putLong(BACKEND_VK_DEVICE, device.address());
            backend.putLong(BACKEND_VK_PHYSICAL, device.getPhysicalDevice().address());
            backend.putLong(BACKEND_VK_PROCADDR,
                    vkGetInstanceProcAddr(device.getPhysicalDevice().getInstance(), "vkGetDeviceProcAddr"));

            header(create, 0, CREATE_CONTEXT_DESC_TYPE_UPSCALE, memAddress(backend));
            // LDR, perceptual (gamma) color; depth is standard [0..1] with a finite far plane.
            create.putInt(CREATE_UPSCALE_FLAGS, UPSCALE_ENABLE_NON_LINEAR_COLORSPACE);
            create.putInt(CREATE_UPSCALE_MAX_RENDER, FrameTargets.scene.width());
            create.putInt(CREATE_UPSCALE_MAX_RENDER + 4, FrameTargets.scene.height());
            create.putInt(CREATE_UPSCALE_MAX_UPSCALE, FrameTargets.display.width());
            create.putInt(CREATE_UPSCALE_MAX_UPSCALE + 4, FrameTargets.display.height());
            create.putLong(CREATE_UPSCALE_MESSAGE, NULL);

            int rc = createContext(pContext, memAddress(create));
            if (rc != RETURN_OK) {
                Initializer.LOGGER.error("[fsr3] ffxCreateContext failed: {}; falling back to FSR 1", rc);
                failed = true;
                return false;
            }
        } finally {
            memFree(backend);
            memFree(create);
        }

        contextValid = true;
        contextGeneration = FrameTargets.generation();
        resetNext = true;
        if (dispatchDesc == null) dispatchDesc = memCalloc(DISPATCH_UPSCALE_SIZE);
        if (reactiveDesc == null) reactiveDesc = memCalloc(DISPATCH_REACTIVE_SIZE);
        reactive.ensure(FrameTargets.scene.width(), FrameTargets.scene.height());
        Initializer.LOGGER.info("[fsr3] context {}x{} -> {}x{} ({})",
                FrameTargets.scene.width(), FrameTargets.scene.height(),
                FrameTargets.display.width(), FrameTargets.display.height(), providerVersion(pContext));
        return true;
    }

    /**
     * Records the upscale. The scene color is in COLOR_ATTACHMENT_OPTIMAL; the motion
     * vector pass already left depth and vectors in SHADER_READ_ONLY_OPTIMAL. Leaves the
     * display color in COLOR_ATTACHMENT_OPTIMAL. Returns false when nothing was recorded.
     */
    static boolean record(VkCommandBuffer cmd, MemoryStack stack) {
        if (!ensureContext()) return false;
        RenderTarget scene = FrameTargets.scene, display = FrameTargets.display;
        StorageImage mv = MotionVectorPass.motionVectors;

        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        // The previous frame's swapchain copy read the display color.
        FrameTargets.imageBarrier(pre.get(1), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        boolean useReactive = FrameTargets.opaqueTaken() && generateReactive(cmd, scene, stack);

        long now = System.nanoTime();
        float frameMs = lastDispatchNanos == 0 ? 16.6f
                : Math.max(0.1f, Math.min(200.0f, (now - lastDispatchNanos) / 1.0e6f));
        lastDispatchNanos = now;

        ByteBuffer d = dispatchDesc;
        for (int i = 0; i < DISPATCH_UPSCALE_SIZE; i += 4) d.putInt(i, 0);
        header(d, 0, DISPATCH_DESC_TYPE_UPSCALE, NULL);
        d.putLong(DU_CMD, cmd.address());
        resource(d, DU_COLOR, scene.colorImage(), scene.width(), scene.height(),
                FORMAT_R8G8B8A8_UNORM, USAGE_RENDERTARGET, STATE_COMPUTE_READ);
        resource(d, DU_DEPTH, scene.depthImage(), scene.width(), scene.height(),
                FORMAT_R32_FLOAT, USAGE_DEPTHTARGET, STATE_COMPUTE_READ);
        resource(d, DU_MV, mv.image, mv.width, mv.height,
                FORMAT_R16G16_FLOAT, USAGE_READ_ONLY, STATE_COMPUTE_READ);
        noResource(d, DU_EXPOSURE);
        if (useReactive) {
            resource(d, DU_REACTIVE, reactive.image, reactive.width, reactive.height,
                    FORMAT_R32_FLOAT, USAGE_READ_ONLY, STATE_COMPUTE_READ);
        } else {
            noResource(d, DU_REACTIVE);
        }
        noResource(d, DU_TC);
        resource(d, DU_OUTPUT, display.colorImage(), display.width(), display.height(),
                FORMAT_R8G8B8A8_UNORM, USAGE_UAV | USAGE_RENDERTARGET, STATE_UNORDERED_ACCESS);
        d.putFloat(DU_JITTER, CameraHistory.jitterX());
        d.putFloat(DU_JITTER + 4, CameraHistory.jitterY());
        // Our vectors are UV deltas; FSR scales them by motionVectorScale / renderSize.
        d.putFloat(DU_MV_SCALE, scene.width());
        d.putFloat(DU_MV_SCALE + 4, scene.height());
        d.putInt(DU_RENDER_SIZE, scene.width());
        d.putInt(DU_RENDER_SIZE + 4, scene.height());
        d.putInt(DU_UPSCALE_SIZE, display.width());
        d.putInt(DU_UPSCALE_SIZE + 4, display.height());
        float sharpness = Initializer.CONFIG.effectiveFsr3Sharpness();
        d.put(DU_SHARPEN, (byte) (sharpness > 0.0f ? 1 : 0));
        d.putFloat(DU_SHARPNESS, sharpness);
        d.putFloat(DU_FRAME_TIME, frameMs);
        d.putFloat(DU_PRE_EXPOSURE, 1.0f);
        d.put(DU_RESET, (byte) (resetNext || !CameraHistory.hasPrevious() ? 1 : 0));
        d.putFloat(DU_NEAR, CameraHistory.near());
        d.putFloat(DU_FAR, CameraHistory.far());
        d.putFloat(DU_FOV, CameraHistory.fovY());
        // One block is a meter.
        d.putFloat(DU_METERS, 1.0f);
        // VULKANMOD_FSR3_DEBUG=1: FSR's own debug view (inputs, history, reactivity).
        d.putInt(DU_FLAGS, DEBUG_VIEW ? 1 : 0);

        int rc = FfxApi.dispatch(pContext, memAddress(d));
        resetNext = false;
        // ffx-api bound its own compute pipelines and descriptor sets (B6 hook).
        Drawer.invalidateBindState();
        if (rc != RETURN_OK) {
            Initializer.LOGGER.error("[fsr3] ffxDispatch failed: {}; falling back to FSR 1", rc);
            failed = true;
        }

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
        return true;
    }

    /**
     * F2: FSR's auto-reactive pass marks pixels that changed between the opaque-only copy
     * and the final scene color (particles, water, weather). continuous mask.
     * Expects the scene color already in SHADER_READ_ONLY_OPTIMAL.
     */
    private static boolean generateReactive(VkCommandBuffer cmd, RenderTarget scene, MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(1, stack);
        // The previous frame's upscale read the mask.
        FrameTargets.imageBarrier(pre.get(0), reactive.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        ByteBuffer r = reactiveDesc;
        for (int i = 0; i < DISPATCH_REACTIVE_SIZE; i += 4) r.putInt(i, 0);
        header(r, 0, DISPATCH_DESC_TYPE_UPSCALE_GENERATEREACTIVEMASK, NULL);
        r.putLong(DR_CMD, cmd.address());
        StorageImage opaque = FrameTargets.opaqueColor;
        resource(r, DR_OPAQUE, opaque.image, opaque.width, opaque.height,
                FORMAT_R8G8B8A8_UNORM, USAGE_READ_ONLY, STATE_COMPUTE_READ);
        resource(r, DR_COLOR, scene.colorImage(), scene.width(), scene.height(),
                FORMAT_R8G8B8A8_UNORM, USAGE_RENDERTARGET, STATE_COMPUTE_READ);
        resource(r, DR_OUT, reactive.image, reactive.width, reactive.height,
                FORMAT_R32_FLOAT, USAGE_UAV, STATE_UNORDERED_ACCESS);
        r.putInt(DR_RENDER_SIZE, scene.width());
        r.putInt(DR_RENDER_SIZE + 4, scene.height());
        // Continuous, amplified mask instead of AMD's binary default (cutoff 0.2): rain and
        // other faint alpha-blended layers change the color by less than the cutoff.
        r.putFloat(DR_SCALE, REACTIVE_SCALE);
        r.putFloat(DR_CUTOFF, 0.2f);
        r.putFloat(DR_BINARY, 0.9f);
        r.putInt(DR_FLAGS, AUTOREACTIVE_APPLY_TONEMAP | AUTOREACTIVE_USE_COMPONENTS_MAX);

        int rc = FfxApi.dispatch(pContext, memAddress(r));
        if (rc != RETURN_OK) {
            Initializer.LOGGER.warn("[fsr3] reactive mask dispatch failed: {}", rc);
            return false;
        }

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), reactive.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
        return true;
    }

    private static void releaseContext() {
        if (contextValid) {
            int rc = destroyContext(pContext);
            if (rc != RETURN_OK) Initializer.LOGGER.warn("[fsr3] ffxDestroyContext: {}", rc);
            contextValid = false;
        }
    }

    static void destroy() {
        releaseContext();
        if (pContext != NULL) { nmemFree(pContext); pContext = NULL; }
        if (dispatchDesc != null) { memFree(dispatchDesc); dispatchDesc = null; }
        if (reactiveDesc != null) { memFree(reactiveDesc); reactiveDesc = null; }
        reactive.destroy();
        contextGeneration = -1;
    }
}
