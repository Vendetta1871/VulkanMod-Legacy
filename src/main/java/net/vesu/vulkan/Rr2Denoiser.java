package net.vesu.vulkan;

import net.vesu.Env;
import net.vesu.Initializer;
import net.vesu.vulkan.rt.RtBuffer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.io.File;
import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Ray Reconstruction for the path-traced lighting (RtLightingPass, pathtrace.rgen): the RR2 network
 * (a reimplementation of NVIDIA's DLSS Ray Reconstruction, project dlss-rr) through its native Vulkan
 * runtime librr2vk.so. The runtime records its CUDA kernels into the frame's command buffer with
 * VK_NV_cuda_kernel_launch on buffer device addresses: no CUDA context, no interop, NVIDIA only.
 *
 * Files (not shipped: the weights come from the NVIDIA SDK and stay on the user's machine), from
 * VESU_RR2_DIR or config/vesu/rr2: librr2vk.so, rr2.cubin (built for the GPU's compute capability),
 * rr2vk.pack (the weight pack, rr2vk/export.py). VESU_RR=0 disables it.
 *
 * Inputs: one device buffer, written by pathtrace.rgen and rt_pt_resolve.comp: a 256-byte header
 * (uint plane offsets in floats, see {@link #HEADER}) and seven float32 planes of W0 x H0 pixels:
 * noisy linear radiance (3), linear view distance (1), motion vectors in pixels towards the previous
 * frame (2), world normals (3), linear roughness (1), diffuse albedo (3), specular albedo (3).
 * Output: float32 (H, W, 3) at the size padded to multiples of 32 (rr2vk_output).
 */
final class Rr2Denoiser {

    /** Channels per pixel of the seven input planes, in rr2vk's order. */
    static final int[] LD = {3, 1, 2, 3, 1, 3, 3};
    /** Header: plane offsets (floats) 0-6, width 8, height 9, padded output width 10; jitter 12-13, wave time 14, camera xz modulo 1024 15-16 (floats). */
    static final int HEADER = 256;

    private static SharedLibrary library;
    /** rr2vk_create_args / rr2vk_record_args take one struct (layouts in rr2vk.h), rr2vk_destroy the handle. */
    private static long fnCreate, fnDestroy, fnRecord;
    private static boolean loadAttempted, failed;
    private static String cubin, pack;

    private static long handle;
    private static int width, height, outW, outH;
    private static RtBuffer inputs;
    private static final long[] planes = new long[7];
    private static long outputBuffer, outputOffset, outputAddress;
    private static boolean fresh = true;

    private Rr2Denoiser() {}

    /** The device can launch CUDA kernels, the files are there and creation never failed. */
    static boolean available() {
        if (failed || "0".equals(Env.get("VESU_RR"))) return false;
        DeviceInfo info = Vulkan.getDeviceInfo();
        if (info == null || info.features == null || !info.features.cudaKernelLaunch
                || !info.features.bufferDeviceAddress) {
            return false;
        }
        return load();
    }

    private static synchronized boolean load() {
        if (loadAttempted) return library != null;
        loadAttempted = true;
        String dir = Env.get("VESU_RR2_DIR");
        File base = dir != null && !dir.isEmpty() ? new File(dir)
                : new File(net.minecraft.client.Minecraft.getMinecraft().gameDir, "config/vesu/rr2");
        File lib = new File(base, "librr2vk.so"), cu = new File(base, "rr2.cubin"), pk = new File(base, "rr2vk.pack");
        if (!lib.isFile() || !cu.isFile() || !pk.isFile()) {
            Initializer.LOGGER.info("[rr] no RR2 runtime in {} (librr2vk.so, rr2.cubin, rr2vk.pack); NRD denoises",
                    base.getAbsolutePath());
            return false;
        }
        try {
            SharedLibrary l = APIUtil.apiCreateLibrary(lib.getAbsolutePath());
            fnCreate = l.getFunctionAddress("rr2vk_create_args");
            fnDestroy = l.getFunctionAddress("rr2vk_destroy");
            fnRecord = l.getFunctionAddress("rr2vk_record_args");
            if (fnCreate == NULL || fnDestroy == NULL || fnRecord == NULL) {
                l.free();
                Initializer.LOGGER.warn("[rr] {} lacks the rr2vk entry points", lib);
                return false;
            }
            library = l;
            cubin = cu.getAbsolutePath();
            pack = pk.getAbsolutePath();
            Initializer.LOGGER.info("[rr] loaded {}", lib.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[rr] could not load {}: {}", lib, t.getMessage());
            return false;
        }
    }

    /** (Re)creates the network's frame arrays and the input buffer for this render size. */
    static boolean ensure(int w, int h) {
        if (!available()) return false;
        if (handle != NULL && w == width && h == height) return true;
        destroy();
        VkDevice device = Vulkan.getDevice();
        // rr2vk uploads its weights through the graphics queue and waits for it.
        FrameGeneration.waitForPresents();
        vkDeviceWaitIdle(device);
        ByteBuffer a = MemoryUtil.memCalloc(96);
        ByteBuffer c = MemoryUtil.memUTF8(cubin), p = MemoryUtil.memUTF8(pack);
        try {
            a.putLong(0, device.getPhysicalDevice().address()).putLong(8, device.address())
                    .putLong(16, Vulkan.getGraphicsQueue().address())
                    .putLong(24, MemoryUtil.memAddress(c)).putLong(32, MemoryUtil.memAddress(p))
                    .putInt(40, Vulkan.findQueueFamilies(device.getPhysicalDevice()).graphicsFamily)
                    .putInt(44, w).putInt(48, h);
            int rc = JNI.callPI(MemoryUtil.memAddress(a), fnCreate);
            handle = rc == 0 ? a.getLong(56) : NULL;
            outputAddress = a.getLong(64);
            outputBuffer = a.getLong(72);
            outputOffset = a.getLong(80);
            outH = a.getInt(88);
            outW = a.getInt(92);
        } finally {
            MemoryUtil.memFree(a);
            MemoryUtil.memFree(c);
            MemoryUtil.memFree(p);
        }
        if (handle == NULL) {
            failed = true;
            Initializer.LOGGER.error("[rr] rr2vk_create failed at {}x{}; NRD denoises", w, h);
            return false;
        }
        width = w;
        height = h;
        long size = HEADER;
        for (int i = 0; i < 7; i++) {
            planes[i] = size;
            size = (size + (long) w * h * LD[i] * 4 + 255) & ~255L;
        }
        inputs = RtBuffer.create(size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
        fresh = true;
        Initializer.LOGGER.info("[rr] RR2 at {}x{} ({} MB of inputs)", w, h, size >> 20);
        return true;
    }

    static long inputBuffer() { return inputs.buffer; }
    static long inputBytes() { return inputs.size; }
    static long outputBuffer() { return outputBuffer; }
    static long outputOffset() { return outputOffset; }
    /** Bytes of the output: float32 x 3 at the padded size. */
    static long outputBytes() { return (long) outW * outH * 12; }
    static int outputWidth() { return outW; }

    /** Writes this frame's header into the input buffer (before the passes that fill the planes). */
    static void writeHeader(VkCommandBuffer cmd, float jx, float jy, float time, float camX, float camZ, MemoryStack stack) {
        ByteBuffer hdr = stack.calloc(80);
        for (int i = 0; i < 7; i++) hdr.putInt(i * 4, (int) (planes[i] / 4));
        hdr.putInt(32, width).putInt(36, height).putInt(40, outW);
        hdr.putFloat(48, jx).putFloat(52, jy).putFloat(56, time).putFloat(60, camX).putFloat(64, camZ);
        vkCmdUpdateBuffer(cmd, inputs.buffer, 0, hdr);
    }

    /**
     * Records the network on the filled inputs. The runtime starts and ends with full memory
     * barriers, so the planes written before and the output read after need no extra ones.
     */
    static void record(VkCommandBuffer cmd, float jx, float jy, boolean reset, MemoryStack stack) {
        // RR2VKRecordArgs: handle, command buffer, RR2VKInputs (seven addresses, seven ints), jitter, reset.
        ByteBuffer a = stack.calloc(120);
        a.putLong(0, handle).putLong(8, cmd.address());
        long base = inputs.deviceAddress();
        for (int i = 0; i < 7; i++) {
            a.putLong(16 + i * 8, base + planes[i]);
            a.putInt(72 + i * 4, LD[i]);
        }
        a.putFloat(104, jx).putFloat(108, jy).putInt(112, reset || fresh ? 1 : 0);
        JNI.callPV(MemoryUtil.memAddress(a), fnRecord);
        fresh = false;
    }

    /** The device must be idle. */
    static void destroy() {
        if (handle != NULL) {
            JNI.callPV(handle, fnDestroy);
            handle = NULL;
        }
        if (inputs != null) {
            inputs.destroyNow();
            inputs = null;
        }
        width = height = 0;
    }
}
