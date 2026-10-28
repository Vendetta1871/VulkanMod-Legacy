package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.CameraHistory;
import org.joml.Matrix4f;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * NVIDIA NRD (RELAX_DIFFUSE) for the RT block light and indirect light, through
 * libvulkanmod_nrd.so (native/nrd-linux): it records into the frame's command buffer
 * between lighting.rgen and rt_composite.comp. Optional: without the library (or with
 * VULKANMOD_NRD=0) the passes keep their own temporal and spatial filter.
 * Struct layouts mirror native/nrd-linux/shim/vm_nrd.cpp.
 */
final class NrdDenoiser {

    private static final String LIB = "libvulkanmod_nrd.so";
    private static final int CREATE_SIZE = 72, FRAME_SIZE = 416, IMAGES = 304;
    /** Image slots in the frame struct, in order. */
    static final int MV = 0, NORMAL_ROUGHNESS = 1, VIEWZ = 2, BLOCK_IN = 3, BLOCK_OUT = 4, GI_IN = 5, GI_OUT = 6;

    private static SharedLibrary library;
    private static long fnCreate, fnDestroy, fnDenoise;
    private static boolean loadAttempted, failed;
    private static int width, height, frameIndex;

    private static final Matrix4f tmp = new Matrix4f();

    private NrdDenoiser() {}

    /** The library is present, NRD is not disabled and creation never failed. */
    static boolean available() {
        if (failed || "0".equals(System.getenv("VULKANMOD_NRD"))) return false;
        return load();
    }

    private static synchronized boolean load() {
        if (loadAttempted) return library != null;
        loadAttempted = true;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        String path = System.getenv("VULKANMOD_NRD_LIB");
        if (path == null || path.isEmpty()) {
            String bundled = net.vulkanmod.vulkan.ffx.FfxApi.extractBundled("linux-x64/" + LIB, LIB);
            if (bundled == null) {
                Initializer.LOGGER.info("[nrd] not bundled; RT passes use their own filter");
                return false;
            }
            path = bundled;
        }
        try {
            SharedLibrary lib = APIUtil.apiCreateLibrary(path);
            fnCreate = lib.getFunctionAddress("vmnrd_create");
            fnDestroy = lib.getFunctionAddress("vmnrd_destroy");
            fnDenoise = lib.getFunctionAddress("vmnrd_denoise");
            if (fnCreate == NULL || fnDestroy == NULL || fnDenoise == NULL) {
                lib.free();
                Initializer.LOGGER.warn("[nrd] {} lacks the vmnrd entry points", path);
                return false;
            }
            library = lib;
            Initializer.LOGGER.info("[nrd] loaded {}", path);
            return true;
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[nrd] could not load {}: {}", path, t.getMessage());
            return false;
        }
    }

    /** (Re)creates the denoisers for this render size. False when NRD cannot be used. */
    static boolean ensure(int w, int h) {
        if (!available()) return false;
        if (w == width && h == height) return true;
        destroy();
        VkDevice device = Vulkan.getDevice();
        List<String> inst = Vulkan.enabledInstanceExtensions();
        // NRD runs compute only: hiding the ray-tracing extensions keeps NRI from resolving
        // ray-tracing entry points beyond what the device enabled (maintenance1).
        List<String> dev = Vulkan.enabledDeviceExtensions().stream()
                .filter(e -> !e.contains("ray_tracing") && !e.contains("acceleration_structure")
                        && !e.contains("ray_query") && !e.contains("deferred_host_operations"))
                .toList();
        ByteBuffer desc = MemoryUtil.memCalloc(CREATE_SIZE);
        long[] strings = new long[inst.size() + dev.size()];
        long instArray = MemoryUtil.nmemCalloc(Math.max(1, inst.size()), 8);
        long devArray = MemoryUtil.nmemCalloc(Math.max(1, dev.size()), 8);
        try {
            for (int i = 0; i < inst.size(); i++) {
                strings[i] = MemoryUtil.memAddress(MemoryUtil.memUTF8(inst.get(i)));
                MemoryUtil.memPutAddress(instArray + 8L * i, strings[i]);
            }
            for (int i = 0; i < dev.size(); i++) {
                strings[inst.size() + i] = MemoryUtil.memAddress(MemoryUtil.memUTF8(dev.get(i)));
                MemoryUtil.memPutAddress(devArray + 8L * i, strings[inst.size() + i]);
            }
            desc.putLong(0, device.getPhysicalDevice().getInstance().address())
                    .putLong(8, device.getPhysicalDevice().address())
                    .putLong(16, device.address())
                    .putLong(24, instArray).putLong(32, devArray)
                    .putInt(40, inst.size()).putInt(44, dev.size())
                    .putInt(48, Vulkan.findQueueFamilies(device.getPhysicalDevice()).graphicsFamily)
                    .putInt(52, w).putInt(56, h)
                    // Frames in flight plus one: NRD's constants must outlive every queued frame.
                    .putInt(60, 3)
                    .putInt(64, 3);
            int rc = JNI.callPI(MemoryUtil.memAddress(desc), fnCreate);
            if (rc != 0) {
                failed = true;
                Initializer.LOGGER.error("[nrd] create failed ({}); RT passes use their own filter", rc);
                return false;
            }
        } finally {
            for (long s : strings) if (s != NULL) MemoryUtil.nmemFree(s);
            MemoryUtil.nmemFree(instArray);
            MemoryUtil.nmemFree(devArray);
            MemoryUtil.memFree(desc);
        }
        width = w;
        height = h;
        frameIndex = 0;
        Initializer.LOGGER.info("[nrd] RELAX_DIFFUSE x2 at {}x{}", w, h);
        return true;
    }

    /**
     * Records both denoisers. {@code images[slot] = {VkImage, VkFormat, layout}} with layout
     * 0 = GENERAL, 1 = SHADER_READ_ONLY_OPTIMAL; they are left in the same layouts.
     */
    static void denoise(VkCommandBuffer cmd, long[][] images, boolean reset, MemoryStack stack) {
        ByteBuffer f = stack.calloc(FRAME_SIZE);
        f.putLong(0, cmd.address());
        CameraHistory.proj().get(8, f);
        CameraHistory.prevProj().get(72, f);
        // "World" is camera-relative: the previous view moves by the camera delta.
        CameraHistory.view().get(136, f);
        tmp.set(CameraHistory.prevView()).translate(
                (float) (CameraHistory.camX() - CameraHistory.prevCamX()),
                (float) (CameraHistory.camY() - CameraHistory.prevCamY()),
                (float) (CameraHistory.camZ() - CameraHistory.prevCamZ()));
        tmp.get(200, f);
        f.putFloat(264, CameraHistory.jitterX()).putFloat(268, CameraHistory.jitterY());
        f.putFloat(272, prevJitterX).putFloat(276, prevJitterY);
        prevJitterX = CameraHistory.jitterX();
        prevJitterY = CameraHistory.jitterY();
        f.putFloat(280, 1.0f).putFloat(284, 1.0f);
        f.putInt(288, frameIndex++).putInt(292, reset ? 1 : 0).putFloat(296, 100000.0f);
        for (int i = 0; i < images.length; i++) {
            int o = IMAGES + i * 16;
            f.putLong(o, images[i][0]).putInt(o + 8, (int) images[i][1]).putInt(o + 12, (int) images[i][2]);
        }
        int rc = JNI.callPI(MemoryUtil.memAddress(f), fnDenoise);
        if (rc != 0) Initializer.LOGGER.warn("[nrd] denoise failed: {}", rc);
    }

    private static float prevJitterX, prevJitterY;

    /** The device must be idle (or NRD waits for it: autoWaitForIdle). */
    static void destroy() {
        if (library != null && width > 0) JNI.callV(fnDestroy);
        width = height = 0;
    }
}
