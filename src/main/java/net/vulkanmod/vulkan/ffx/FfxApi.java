package net.vulkanmod.vulkan.ffx;

import net.vulkanmod.Initializer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.SharedLibrary;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Minimal binding of AMD's ffx-api C ABI (FidelityFX SDK 1.1.4, amd_fidelityfx_vk).
 * Calls go through LWJGL's JNI trampolines; structs are filled at fixed offsets.
 * Offsets were generated with offsetof() from the SDK headers (x86_64; identical on
 * Windows and Linux because the structs hold no `long` and `bool` is one byte).
 */
public final class FfxApi {

    // --- return codes ---
    public static final int RETURN_OK = 0;

    // --- struct type ids ---
    public static final long CREATE_CONTEXT_DESC_TYPE_BACKEND_VK = 0x0000003L;
    public static final long QUERY_DESC_TYPE_GET_PROVIDER_VERSION = 6L;
    public static final long CREATE_CONTEXT_DESC_TYPE_UPSCALE = 0x00010000L;
    public static final long DISPATCH_DESC_TYPE_UPSCALE = 0x00010001L;
    public static final long QUERY_DESC_TYPE_UPSCALE_GETJITTERPHASECOUNT = 0x00010004L;
    public static final long QUERY_DESC_TYPE_UPSCALE_GETJITTEROFFSET = 0x00010005L;
    public static final long DISPATCH_DESC_TYPE_UPSCALE_GENERATEREACTIVEMASK = 0x00010006L;

    // --- FfxApiDispatchUpscaleAutoreactiveFlags ---
    public static final int AUTOREACTIVE_APPLY_TONEMAP = 1;
    public static final int AUTOREACTIVE_APPLY_THRESHOLD = 1 << 2;
    public static final int AUTOREACTIVE_USE_COMPONENTS_MAX = 1 << 3;

    // --- ffxCreateContextDescUpscale.flags ---
    public static final int UPSCALE_ENABLE_HIGH_DYNAMIC_RANGE = 1;
    public static final int UPSCALE_ENABLE_DEPTH_INVERTED = 1 << 3;
    public static final int UPSCALE_ENABLE_AUTO_EXPOSURE = 1 << 5;
    public static final int UPSCALE_ENABLE_DEBUG_CHECKING = 1 << 7;
    public static final int UPSCALE_ENABLE_NON_LINEAR_COLORSPACE = 1 << 8;

    // --- FfxApiSurfaceFormat (subset) ---
    public static final int FORMAT_R16G16B16A16_FLOAT = 4;
    public static final int FORMAT_R8G8B8A8_UNORM = 10;
    public static final int FORMAT_B8G8R8A8_UNORM = 14;
    public static final int FORMAT_R16G16_FLOAT = 18;
    public static final int FORMAT_R8_UNORM = 25;
    public static final int FORMAT_R8G8_UNORM = 26;
    public static final int FORMAT_R32_FLOAT = 28;

    // --- FfxApiResourceUsage ---
    public static final int USAGE_READ_ONLY = 0;
    public static final int USAGE_RENDERTARGET = 1;
    public static final int USAGE_UAV = 1 << 1;
    public static final int USAGE_DEPTHTARGET = 1 << 2;

    // --- FfxApiResourceState ---
    public static final int STATE_UNORDERED_ACCESS = 1 << 1;
    public static final int STATE_COMPUTE_READ = 1 << 2;

    // --- FfxApiResourceType ---
    public static final int RESOURCE_TYPE_TEXTURE2D = 2;

    // --- struct layouts (from offsetof) ---
    public static final int HEADER_SIZE = 16, HEADER_TYPE = 0, HEADER_PNEXT = 8;
    public static final int RES_SIZE = 48, RES_RESOURCE = 0, RES_DESC = 8, RES_STATE = 40;
    public static final int DESC_TYPE = 0, DESC_FORMAT = 4, DESC_WIDTH = 8, DESC_HEIGHT = 12, DESC_DEPTH = 16,
            DESC_MIPS = 20, DESC_FLAGS = 24, DESC_USAGE = 28;
    public static final int BACKEND_VK_SIZE = 40, BACKEND_VK_DEVICE = 16, BACKEND_VK_PHYSICAL = 24, BACKEND_VK_PROCADDR = 32;
    public static final int CREATE_UPSCALE_SIZE = 48, CREATE_UPSCALE_FLAGS = 16, CREATE_UPSCALE_MAX_RENDER = 20,
            CREATE_UPSCALE_MAX_UPSCALE = 28, CREATE_UPSCALE_MESSAGE = 40;
    public static final int DISPATCH_UPSCALE_SIZE = 432;
    public static final int DU_CMD = 16, DU_COLOR = 24, DU_DEPTH = 72, DU_MV = 120, DU_EXPOSURE = 168, DU_REACTIVE = 216,
            DU_TC = 264, DU_OUTPUT = 312, DU_JITTER = 360, DU_MV_SCALE = 368, DU_RENDER_SIZE = 376,
            DU_UPSCALE_SIZE = 384, DU_SHARPEN = 392, DU_SHARPNESS = 396, DU_FRAME_TIME = 400,
            DU_PRE_EXPOSURE = 404, DU_RESET = 408, DU_NEAR = 412, DU_FAR = 416, DU_FOV = 420,
            DU_METERS = 424, DU_FLAGS = 428;
    public static final int QUERY_JPC_SIZE = 32, QJPC_RENDER_W = 16, QJPC_DISPLAY_W = 20, QJPC_OUT = 24;
    public static final int QUERY_JO_SIZE = 40, QJO_INDEX = 16, QJO_PHASES = 20, QJO_OUT_X = 24, QJO_OUT_Y = 32;
    public static final int QUERY_VERSION_SIZE = 32, QV_ID = 16, QV_NAME = 24;
    public static final int DISPATCH_REACTIVE_SIZE = 192, DR_CMD = 16, DR_OPAQUE = 24, DR_COLOR = 72, DR_OUT = 120,
            DR_RENDER_SIZE = 168, DR_SCALE = 176, DR_CUTOFF = 180, DR_BINARY = 184, DR_FLAGS = 188;

    private static SharedLibrary library;
    private static long fnCreateContext, fnDestroyContext, fnConfigure, fnQuery, fnDispatch;
    private static boolean loadAttempted;

    private FfxApi() {}

    /**
     * Loads the native library once. Path: VULKANMOD_FFX_LIB, else the platform name
     * (amd_fidelityfx_vk.dll / libamd_fidelityfx_vk.so) through the system search path.
     */
    public static synchronized boolean load() {
        if (loadAttempted) return library != null;
        loadAttempted = true;
        String path = System.getenv("VULKANMOD_FFX_LIB");
        if (path == null || path.isEmpty()) {
            path = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "amd_fidelityfx_vk.dll" : "libamd_fidelityfx_vk.so";
        }
        try {
            SharedLibrary lib = APIUtil.apiCreateLibrary(path);
            fnCreateContext = lib.getFunctionAddress("ffxCreateContext");
            fnDestroyContext = lib.getFunctionAddress("ffxDestroyContext");
            fnConfigure = lib.getFunctionAddress("ffxConfigure");
            fnQuery = lib.getFunctionAddress("ffxQuery");
            fnDispatch = lib.getFunctionAddress("ffxDispatch");
            if (fnCreateContext == NULL || fnDestroyContext == NULL || fnConfigure == NULL
                    || fnQuery == NULL || fnDispatch == NULL) {
                lib.free();
                Initializer.LOGGER.warn("[ffx] {} lacks the ffx-api entry points", path);
                return false;
            }
            library = lib;
            Initializer.LOGGER.info("[ffx] loaded {}", lib.getPath() != null ? lib.getPath() : path);
            return true;
        } catch (Throwable t) {
            Initializer.LOGGER.info("[ffx] native library not available ({}): {}", path, t.getMessage());
            return false;
        }
    }

    public static boolean isLoaded() {
        return library != null;
    }

    // --- raw calls; pointer arguments are native addresses ---

    public static int createContext(long pContext, long desc) {
        return JNI.callPPPI(pContext, desc, NULL, fnCreateContext);
    }

    public static int destroyContext(long pContext) {
        return JNI.callPPI(pContext, NULL, fnDestroyContext);
    }

    public static int configure(long pContext, long desc) {
        return JNI.callPPI(pContext, desc, fnConfigure);
    }

    public static int query(long pContext, long desc) {
        return JNI.callPPI(pContext, desc, fnQuery);
    }

    public static int dispatch(long pContext, long desc) {
        return JNI.callPPI(pContext, desc, fnDispatch);
    }

    // --- struct helpers ---

    public static void header(ByteBuffer b, int offset, long type, long pNext) {
        b.putLong(offset + HEADER_TYPE, type);
        b.putLong(offset + HEADER_PNEXT, pNext);
    }

    /** Fills an FfxApiResource for a 2D single-mip image. */
    public static void resource(ByteBuffer b, int offset, long vkImage, int width, int height,
                         int ffxFormat, int usage, int state) {
        b.putLong(offset + RES_RESOURCE, vkImage);
        int d = offset + RES_DESC;
        b.putInt(d + DESC_TYPE, RESOURCE_TYPE_TEXTURE2D);
        b.putInt(d + DESC_FORMAT, ffxFormat);
        b.putInt(d + DESC_WIDTH, width);
        b.putInt(d + DESC_HEIGHT, height);
        b.putInt(d + DESC_DEPTH, 1);
        b.putInt(d + DESC_MIPS, 1);
        b.putInt(d + DESC_FLAGS, 0);
        b.putInt(d + DESC_USAGE, usage);
        b.putInt(offset + RES_STATE, state);
    }

    /** Leaves an FfxApiResource empty (optional input not provided). */
    public static void noResource(ByteBuffer b, int offset) {
        for (int i = 0; i < RES_SIZE; i += 4) b.putInt(offset + i, 0);
    }

    /** Provider version name of a context, or null. */
    public static String providerVersion(long pContext) {
        ByteBuffer q = memCalloc(QUERY_VERSION_SIZE);
        try {
            header(q, 0, QUERY_DESC_TYPE_GET_PROVIDER_VERSION, NULL);
            if (query(pContext, memAddress(q)) != RETURN_OK) return null;
            long name = q.getLong(QV_NAME);
            return name != NULL ? memUTF8(name) : null;
        } finally {
            memFree(q);
        }
    }
}
