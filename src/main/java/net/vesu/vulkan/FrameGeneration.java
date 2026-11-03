package net.vesu.vulkan;

import net.vesu.Env;
import net.vesu.Initializer;
import net.vesu.render.CameraHistory;
import net.vesu.vulkan.ffx.FfxApi;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static net.vesu.vulkan.ffx.FfxApi.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * FSR 3.1 frame generation (F9) through ffx-api's frame interpolation swapchain. The
 * swapchain context replaces the game's swapchain: acquire, present and the image list
 * go through its replacement functions, and on present it interpolates a frame between
 * the last two and presents both from its own queues and pacing thread.
 *
 * Per frame, a frame that went through FSR 3 configures generation on with a copy of
 * the upscaled color before the hand and the GUI (the HUD-less image the swapchain uses
 * to keep the UI out of the interpolation) and dispatches the "prepare" pass with the
 * render-resolution depth and motion vectors. Other frames (menus, shader packs, other
 * upscalers) configure it off and are presented as they are.
 */
public final class FrameGeneration {

    // --- struct type ids and layouts (offsetof() from the SDK 1.1.4 headers, x86_64) ---
    private static final long CREATE_FGSWAPCHAIN_VK = 0x40001L, QUERY_FGSWAPCHAIN_FUNCTIONS_VK = 0x40005L,
            DISPATCH_FGSWAPCHAIN_WAIT_FOR_PRESENTS_VK = 0x40007L, CREATE_FRAMEGENERATION = 0x20001L,
            CONFIGURE_FRAMEGENERATION = 0x20002L, DISPATCH_FRAMEGENERATION_PREPARE = 0x20004L,
            CREATE_FRAMEGENERATION_HUDLESS = 0x20009L, DISPATCH_FRAMEGENERATION_PREPARE_CAMERAINFO = 0x2000aL;
    private static final int SC_SIZE = 248, SC_PHYSICAL = 16, SC_DEVICE = 24, SC_SWAPCHAIN = 32, SC_ALLOCATOR = 40,
            SC_CREATE_INFO = 48, SC_GAME_QUEUE = 152, SC_ASYNC_QUEUE = 176, SC_PRESENT_QUEUE = 200, SC_ACQUIRE_QUEUE = 224,
            QUEUE_FAMILY = 8;
    private static final int FN_SIZE = 72, FN_CREATE = 16, FN_DESTROY = 24, FN_IMAGES = 32, FN_ACQUIRE = 40,
            FN_PRESENT = 48, FN_PRESENT_COUNT = 64;
    private static final int CFG_SIZE = 40, CFG_FLAGS = 16, CFG_DISPLAY = 20, CFG_MAX_RENDER = 28, CFG_FORMAT = 36;
    private static final int HUDLESS_SIZE = 24, HUDLESS_FORMAT = 16;
    private static final int CONF_SIZE = 144, CONF_SWAPCHAIN = 16, CONF_CALLBACK = 40, CONF_CALLBACK_CTX = 48,
            CONF_ENABLED = 56, CONF_ASYNC = 57, CONF_HUDLESS = 64, CONF_FLAGS = 112, CONF_ONLY_GENERATED = 116,
            CONF_RECT = 120, CONF_FRAME_ID = 136;
    private static final int PREP_SIZE = 184, PREP_FRAME_ID = 16, PREP_FLAGS = 24, PREP_CMD = 32, PREP_RENDER_SIZE = 40,
            PREP_JITTER = 48, PREP_MV_SCALE = 56, PREP_FRAME_TIME = 64, PREP_NEAR = 72, PREP_FAR = 76, PREP_FOV = 80,
            PREP_METERS = 84, PREP_DEPTH = 88, PREP_MV = 136;
    private static final int CAM_SIZE = 64, CAM_POSITION = 16, CAM_UP = 28, CAM_RIGHT = 40, CAM_FORWARD = 52;
    private static final int STATE_PIXEL_COMPUTE_READ = 12;
    /** FfxApiDispatchFramegenerationFlags: tear lines, reset indicators, debug view. */
    private static final int DEBUG_FLAGS = 1 | 2 | 4;

    private static final boolean DEBUG = Env.get("VESU_FRAMEGEN_DEBUG") != null;

    /** ffxContext slots (pointer-sized native memory). */
    private static long pSwapContext, pContext;
    private static boolean swapContextValid, contextValid;
    private static int contextGeneration = -1;
    private static boolean failed;
    private static long fnCreate, fnDestroy, fnImages, fnAcquire, fnPresent, fnPresentCount;
    private static MethodHandle presentCount;
    private static long frameId;
    private static boolean configuredThisFrame;
    private static long lastPrepareNanos;
    private static ByteBuffer configureDesc, prepareDesc;

    /** HUD-less copies: frames in flight + 1, so the swapchain's reads never meet the next write. */
    private static StorageImage[] hudless = new StorageImage[0];

    private static MemorySegment callbackStub;

    private FrameGeneration() {}

    // --- availability ---

    /**
     * The setting is on, the library is loaded and the device has the queues it needs.
     * Asked when the first swapchain is created, before the frame targets exist: what a
     * frame needs (FSR 3, D32 depth) is decided per frame, see {@link #record}.
     */
    public static boolean wanted() {
        return !failed && Initializer.CONFIG != null && Initializer.CONFIG.effectiveFrameGeneration()
                && FfxApi.load() && queuesUsable();
    }

    /** Distinct queues for the swapchain's present and acquire work (its threads submit there). */
    private static boolean queuesUsable() {
        QueueSetup q = Vulkan.getQueues();
        return q != null && q.presentFamily == q.graphicsFamily && q.auxGraphics.length >= 2;
    }

    /** The swapchain is the frame interpolation swapchain. */
    public static boolean active() {
        return swapContextValid;
    }

    // --- swapchain ---

    /**
     * Replaces the just created {@code swapchain} with the frame interpolation swapchain
     * (the plain one is destroyed by ffx). Returns the new handle, or the old one when the
     * context could not be created (frame generation then stays off).
     */
    static long wrap(VkSwapchainCreateInfoKHR createInfo, long swapchain) {
        if (pSwapContext == NULL) pSwapContext = nmemCalloc(1, 8);
        ByteBuffer desc = memCalloc(SC_SIZE);
        LongBuffer pSwapchain = memAllocLong(1).put(0, swapchain);
        try {
            VkDevice device = Vulkan.getDevice();
            QueueSetup q = Vulkan.getQueues();
            header(desc, 0, CREATE_FGSWAPCHAIN_VK, NULL);
            desc.putLong(SC_PHYSICAL, device.getPhysicalDevice().address());
            desc.putLong(SC_DEVICE, device.address());
            desc.putLong(SC_SWAPCHAIN, memAddress(pSwapchain));
            desc.putLong(SC_ALLOCATOR, NULL);
            memCopy(createInfo.address(), memAddress(desc) + SC_CREATE_INFO, VkSwapchainCreateInfoKHR.SIZEOF);
            // The plain swapchain is what gets replaced, not an old one to retire.
            desc.putLong(SC_CREATE_INFO + VkSwapchainCreateInfoKHR.OLDSWAPCHAIN, VK_NULL_HANDLE);
            queue(desc, SC_GAME_QUEUE, q.graphics, q.graphicsFamily);
            if (q.hasDedicatedAsyncCompute()) queue(desc, SC_ASYNC_QUEUE, q.asyncCompute, q.asyncComputeFamily);
            else queue(desc, SC_ASYNC_QUEUE, q.auxGraphics[0], q.graphicsFamily);
            queue(desc, SC_PRESENT_QUEUE, q.auxGraphics[0], q.graphicsFamily);
            queue(desc, SC_ACQUIRE_QUEUE, q.auxGraphics[1], q.graphicsFamily);

            int rc = createContext(pSwapContext, memAddress(desc));
            if (rc != RETURN_OK) {
                Initializer.LOGGER.error("[fg] frame interpolation swapchain failed: {}; frame generation off", rc);
                failed = true;
                return swapchain;
            }
            swapContextValid = true;
            queryFunctions();
            // A new swapchain counts its presents from zero.
            logNanos = 0;
            Initializer.LOGGER.info("[fg] frame interpolation swapchain ({}), queues {}",
                    providerVersion(pSwapContext), q);
            return pSwapchain.get(0);
        } finally {
            memFree(desc);
            memFree(pSwapchain);
        }
    }

    private static void queue(ByteBuffer desc, int offset, VkQueue queue, int family) {
        desc.putLong(offset, queue.address());
        desc.putInt(offset + QUEUE_FAMILY, family);
        desc.putLong(offset + 16, NULL);
    }

    private static void queryFunctions() {
        ByteBuffer q = memCalloc(FN_SIZE);
        try {
            header(q, 0, QUERY_FGSWAPCHAIN_FUNCTIONS_VK, NULL);
            int rc = query(pSwapContext, memAddress(q));
            if (rc != RETURN_OK) throw new RuntimeException("ffx swapchain replacement functions: " + rc);
            fnCreate = q.getLong(FN_CREATE);
            fnDestroy = q.getLong(FN_DESTROY);
            fnImages = q.getLong(FN_IMAGES);
            fnAcquire = q.getLong(FN_ACQUIRE);
            fnPresent = q.getLong(FN_PRESENT);
            fnPresentCount = q.getLong(FN_PRESENT_COUNT);
            presentCount = fnPresentCount == NULL ? null : Linker.nativeLinker().downcallHandle(
                    MemorySegment.ofAddress(fnPresentCount),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        } finally {
            memFree(q);
        }
    }

    /** Recreation (resize, vsync): the context keeps living, its swapchain is replaced. */
    static int recreate(VkSwapchainCreateInfoKHR createInfo, long oldSwapchain, LongBuffer pSwapchain) {
        waitForPresents();
        releaseContext();
        long ctx = memGetAddress(pSwapContext);
        JNI.callPJPPV(Vulkan.getDevice().address(), oldSwapchain, NULL, ctx, fnDestroy);
        createInfo.oldSwapchain(VK_NULL_HANDLE);
        return JNI.callPPPPPI(Vulkan.getDevice().address(), createInfo.address(), NULL, memAddress(pSwapchain), ctx, fnCreate);
    }

    static int getImages(long swapchain, IntBuffer pCount, LongBuffer pImages) {
        return JNI.callPJPPI(Vulkan.getDevice().address(), swapchain, memAddress(pCount),
                pImages == null ? NULL : memAddress(pImages), fnImages);
    }

    static int acquire(long swapchain, long timeout, long semaphore, long fence, IntBuffer pIndex) {
        return JNI.callPJJJJPI(Vulkan.getDevice().address(), swapchain, timeout, semaphore, fence, memAddress(pIndex), fnAcquire);
    }

    static int present(VkQueue queue, VkPresentInfoKHR info) {
        if (!configuredThisFrame) configure(null, false);
        configuredThisFrame = false;
        int rc = JNI.callPPI(queue.address(), info.address(), fnPresent);
        logPresents();
        return rc;
    }

    /** Destroys both contexts; the interpolation swapchain goes with its context. */
    static void shutdown() {
        if (!swapContextValid) return;
        waitForPresents();
        releaseContext();
        int rc = destroyContext(pSwapContext);
        if (rc != RETURN_OK) Initializer.LOGGER.warn("[fg] destroying the swapchain context: {}", rc);
        swapContextValid = false;
        for (StorageImage h : hudless) h.destroy();
        hudless = new StorageImage[0];
    }

    private static void waitForPresents() {
        if (!swapContextValid) return;
        ByteBuffer d = memCalloc(16);
        try {
            header(d, 0, DISPATCH_FGSWAPCHAIN_WAIT_FOR_PRESENTS_VK, NULL);
            dispatch(pSwapContext, memAddress(d));
        } finally {
            memFree(d);
        }
    }

    // --- per frame ---

    /**
     * After the FSR 3 upscale (display color in COLOR_ATTACHMENT_OPTIMAL; scene depth and
     * motion vectors in SHADER_READ_ONLY_OPTIMAL): copies the HUD-less color, configures
     * generation for this frame and records the prepare pass.
     */
    static void record(VkCommandBuffer cmd, MemoryStack stack) {
        if (!swapContextValid || !ensureContext()) return;
        StorageImage h = hudlessFor(frameId);
        copyHudless(cmd, h, stack);
        if (!configure(h, true)) return;

        RenderTarget scene = FrameTargets.scene;
        StorageImage mv = MotionVectorPass.motionVectors;
        long now = System.nanoTime();
        float frameMs = lastPrepareNanos == 0 ? 16.6f : Math.max(0.1f, Math.min(200.0f, (now - lastPrepareNanos) / 1.0e6f));
        lastPrepareNanos = now;

        ByteBuffer cam = stack.calloc(CAM_SIZE);
        header(cam, 0, DISPATCH_FRAMEGENERATION_PREPARE_CAMERAINFO, NULL);
        org.joml.Matrix4f view = CameraHistory.view();
        cam.putFloat(CAM_POSITION, (float) CameraHistory.camX()).putFloat(CAM_POSITION + 4, (float) CameraHistory.camY())
                .putFloat(CAM_POSITION + 8, (float) CameraHistory.camZ());
        // The rows of the view rotation: right, up and back (the view looks down -Z).
        cam.putFloat(CAM_RIGHT, view.m00()).putFloat(CAM_RIGHT + 4, view.m10()).putFloat(CAM_RIGHT + 8, view.m20());
        cam.putFloat(CAM_UP, view.m01()).putFloat(CAM_UP + 4, view.m11()).putFloat(CAM_UP + 8, view.m21());
        cam.putFloat(CAM_FORWARD, -view.m02()).putFloat(CAM_FORWARD + 4, -view.m12()).putFloat(CAM_FORWARD + 8, -view.m22());

        ByteBuffer d = prepareDesc;
        for (int i = 0; i < PREP_SIZE; i += 4) d.putInt(i, 0);
        header(d, 0, DISPATCH_FRAMEGENERATION_PREPARE, memAddress(cam));
        d.putLong(PREP_FRAME_ID, frameId);
        d.putInt(PREP_FLAGS, DEBUG ? DEBUG_FLAGS : 0);
        d.putLong(PREP_CMD, cmd.address());
        d.putInt(PREP_RENDER_SIZE, scene.width()).putInt(PREP_RENDER_SIZE + 4, scene.height());
        // Same conventions as the upscale (Fsr3Upscaler): FSR jitter, UV-delta vectors.
        d.putFloat(PREP_JITTER, CameraHistory.jitterX()).putFloat(PREP_JITTER + 4, CameraHistory.jitterY());
        d.putFloat(PREP_MV_SCALE, scene.width()).putFloat(PREP_MV_SCALE + 4, scene.height());
        d.putFloat(PREP_FRAME_TIME, frameMs);
        d.putFloat(PREP_NEAR, CameraHistory.near());
        d.putFloat(PREP_FAR, CameraHistory.far());
        d.putFloat(PREP_FOV, CameraHistory.fovY());
        d.putFloat(PREP_METERS, 1.0f);
        resource(d, PREP_DEPTH, scene.depthImage(), scene.width(), scene.height(),
                FORMAT_R32_FLOAT, USAGE_DEPTHTARGET, STATE_COMPUTE_READ);
        resource(d, PREP_MV, mv.image, mv.width, mv.height, FORMAT_R16G16_FLOAT, USAGE_READ_ONLY, STATE_COMPUTE_READ);
        int rc = dispatch(pContext, memAddress(d));
        Drawer.invalidateBindState();
        if (rc != RETURN_OK) {
            Initializer.LOGGER.error("[fg] prepare dispatch failed: {}; frame generation off", rc);
            failed = true;
            Drawer.shouldRecreate = true;
        }
    }

    /** Called once per frame, before present; {@code hud} null turns generation off for the frame. */
    private static boolean configure(StorageImage hud, boolean enabled) {
        configuredThisFrame = true;
        frameId++;
        if (!contextValid) return false;
        ByteBuffer c = configureDesc;
        for (int i = 0; i < CONF_SIZE; i += 4) c.putInt(i, 0);
        header(c, 0, CONFIGURE_FRAMEGENERATION, NULL);
        c.putLong(CONF_SWAPCHAIN, Vulkan.getSwapChain());
        c.putLong(CONF_CALLBACK, callbackStub.address());
        c.putLong(CONF_CALLBACK_CTX, pContext);
        c.put(CONF_ENABLED, (byte) (enabled ? 1 : 0));
        c.put(CONF_ASYNC, (byte) 0);
        if (hud != null) {
            resource(c, CONF_HUDLESS, hud.image, hud.width, hud.height, FORMAT_R8G8B8A8_UNORM,
                    USAGE_READ_ONLY, STATE_PIXEL_COMPUTE_READ);
        }
        c.putInt(CONF_FLAGS, DEBUG ? DEBUG_FLAGS : 0);
        c.put(CONF_ONLY_GENERATED, (byte) 0);
        RenderTarget display = FrameTargets.display;
        c.putInt(CONF_RECT, 0).putInt(CONF_RECT + 4, 0)
                .putInt(CONF_RECT + 8, display.width()).putInt(CONF_RECT + 12, display.height());
        c.putLong(CONF_FRAME_ID, frameId);
        int rc = FfxApi.configure(pContext, memAddress(c));
        if (rc != RETURN_OK) {
            Initializer.LOGGER.error("[fg] configure failed: {}; frame generation off", rc);
            failed = true;
            Drawer.shouldRecreate = true;
            return false;
        }
        return true;
    }

    private static boolean ensureContext() {
        if (contextValid && contextGeneration == FrameTargets.generation()) return true;
        releaseContext();
        if (callbackStub == null) callbackStub = createCallback();
        if (pContext == NULL) pContext = nmemCalloc(1, 8);
        ByteBuffer backend = memCalloc(BACKEND_VK_SIZE);
        ByteBuffer hud = memCalloc(HUDLESS_SIZE);
        ByteBuffer create = memCalloc(CFG_SIZE);
        try {
            VkDevice device = Vulkan.getDevice();
            header(backend, 0, CREATE_CONTEXT_DESC_TYPE_BACKEND_VK, NULL);
            backend.putLong(BACKEND_VK_DEVICE, device.address());
            backend.putLong(BACKEND_VK_PHYSICAL, device.getPhysicalDevice().address());
            backend.putLong(BACKEND_VK_PROCADDR,
                    vkGetInstanceProcAddr(device.getPhysicalDevice().getInstance(), "vkGetDeviceProcAddr"));
            header(hud, 0, CREATE_FRAMEGENERATION_HUDLESS, memAddress(backend));
            hud.putInt(HUDLESS_FORMAT, FORMAT_R8G8B8A8_UNORM);
            header(create, 0, CREATE_FRAMEGENERATION, memAddress(hud));
            // Standard depth with a finite far plane, LDR color, jitter-free camera vectors.
            create.putInt(CFG_FLAGS, 0);
            create.putInt(CFG_DISPLAY, FrameTargets.display.width()).putInt(CFG_DISPLAY + 4, FrameTargets.display.height());
            create.putInt(CFG_MAX_RENDER, FrameTargets.scene.width()).putInt(CFG_MAX_RENDER + 4, FrameTargets.scene.height());
            create.putInt(CFG_FORMAT, surfaceFormat(Vulkan.getSwapChainImageFormat()));
            int rc = createContext(pContext, memAddress(create));
            if (rc != RETURN_OK) {
                Initializer.LOGGER.error("[fg] ffxCreateContext failed: {}; frame generation off", rc);
                failed = true;
                Drawer.shouldRecreate = true;
                return false;
            }
        } finally {
            memFree(backend);
            memFree(hud);
            memFree(create);
        }
        contextValid = true;
        contextGeneration = FrameTargets.generation();
        if (configureDesc == null) configureDesc = memCalloc(CONF_SIZE);
        if (prepareDesc == null) prepareDesc = memCalloc(PREP_SIZE);
        Initializer.LOGGER.info("[fg] context {}x{} -> {}x{} ({})", FrameTargets.scene.width(), FrameTargets.scene.height(),
                FrameTargets.display.width(), FrameTargets.display.height(), providerVersion(pContext));
        return true;
    }

    private static void releaseContext() {
        if (!contextValid) return;
        int rc = destroyContext(pContext);
        if (rc != RETURN_OK) Initializer.LOGGER.warn("[fg] ffxDestroyContext: {}", rc);
        contextValid = false;
        contextGeneration = -1;
    }

    private static StorageImage hudlessFor(long frame) {
        int ring = Drawer.framesInFlight() + 1;
        if (hudless.length != ring) {
            for (StorageImage h : hudless) h.destroy();
            hudless = new StorageImage[ring];
            for (int i = 0; i < ring; i++) {
                hudless[i] = new StorageImage(VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                                | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
            }
        }
        StorageImage h = hudless[(int) Long.remainderUnsigned(frame, ring)];
        h.ensure(FrameTargets.display.width(), FrameTargets.display.height());
        return h;
    }

    private static void copyHudless(VkCommandBuffer cmd, StorageImage h, MemoryStack stack) {
        RenderTarget display = FrameTargets.display;
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(pre.get(0), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        // The swapchain read this copy frames ago, on its own queues (fenced by then).
        FrameTargets.imageBarrier(pre.get(1), h.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);

        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.extent().set(h.width, h.height, 1);
        vkCmdCopyImage(cmd, display.colorImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                h.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(post.get(0), display.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.imageBarrier(post.get(1), h.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    private static int surfaceFormat(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_B8G8R8A8_UNORM -> FORMAT_B8G8R8A8_UNORM;
            case VK_FORMAT_B8G8R8A8_SRGB -> 15;
            case VK_FORMAT_R8G8B8A8_SRGB -> 11;
            case VK_FORMAT_A2B10G10R10_UNORM_PACK32 -> 3;
            default -> FORMAT_R8G8B8A8_UNORM;
        };
    }

    // --- the frame generation callback (called by the swapchain on present) ---

    private static MemorySegment createCallback() {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(FrameGeneration.class, "generate",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
            return Linker.nativeLinker().upcallStub(target,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ffxDispatch(userContext, params): the interpolation itself, into the swapchain's command list. */
    private static int generate(MemorySegment params, MemorySegment userContext) {
        try {
            return FfxApi.dispatch(userContext.address(), params.address());
        } catch (Throwable t) {
            // An exception must not unwind into native code.
            return 1;
        }
    }

    // --- diagnostics ---

    private static long logNanos, logPresents, logFrames;

    /** Under VESU_FRAMEGEN_LOG: presented frames against rendered ones, every two seconds. */
    private static void logPresents() {
        if (Env.get("VESU_FRAMEGEN_LOG") == null || presentCount == null) return;
        logFrames++;
        long now = System.nanoTime();
        if (logNanos == 0) {
            logNanos = now;
            logPresents = presents();
            logFrames = 0;
            return;
        }
        if (now - logNanos < 2_000_000_000L) return;
        long p = presents();
        double s = (now - logNanos) / 1e9;
        Initializer.LOGGER.info("[fg] {} rendered/s, {} presented/s", String.format("%.1f", logFrames / s),
                String.format("%.1f", (p - logPresents) / s));
        logNanos = now;
        logPresents = p;
        logFrames = 0;
    }

    private static long presents() {
        try {
            return (long) presentCount.invokeExact(Vulkan.getSwapChain());
        } catch (Throwable t) {
            return 0;
        }
    }
}
