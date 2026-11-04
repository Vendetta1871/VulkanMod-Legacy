package net.vesu.vulkan;

import net.vesu.Initializer;
import net.vesu.debug.FrameProfiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * GPU time per frame pass (VESU_PROFILE=1): timestamps at fixed marks, read back
 * when the frame slot comes round again (its fence was waited on), averaged and logged
 * every {@link #LOG_EVERY} frames as "[gpu]" with the time from each mark to the next.
 */
public final class GpuTimer {

    /** Marks in frame order; a frame may skip some (no RT, no upscale). */
    public static final int FRAME_START = 0, LOD_START = 1, LOD_CULL = 2, LOD_DRAW = 3, LOD_SSAO = 4, RT_SCENE = 5,
            LIGHT_TRACE = 6, LIGHT_DENOISE = 7, RT_LIGHTING = 8, RT_REFLECT_OPAQUE = 9, SCENE_END = 10,
            RT_REFLECT_TRANSLUCENT = 11, PACK = 12, UPSCALE = 13, FRAME_END = 14;
    /** Each entry names the work between the previous written mark and this one. */
    private static final String[] NAMES = {"start", "opaque", "lodCull", "lodDraw", "lodSsao", "opaque+tlas", "lightTrace",
            "nrd+mv", "composite", "reflOpaque", "translucent", "reflTransl", "pack", "upscale", "hud+copy"};
    private static final int MARKS = NAMES.length, LOG_EVERY = 120;

    private static long pool;
    private static int slots;
    private static float periodNs;
    private static int[] written;
    private static final double[] sums = new double[MARKS];
    private static int frames;

    private GpuTimer() {}

    private static boolean ready() {
        if (!FrameProfiler.ENABLED) return false;
        if (pool != 0L) return true;
        DeviceInfo info = Vulkan.getDeviceInfo();
        if (info == null) return false;
        slots = Drawer.framesInFlight();
        written = new int[slots];
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(Vulkan.getDevice().getPhysicalDevice(), props);
            periodNs = props.limits().timestampPeriod();
            LongBuffer p = stack.mallocLong(1);
            if (vkCreateQueryPool(Vulkan.getDevice(), VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(slots * MARKS), null, p) != VK_SUCCESS) {
                return false;
            }
            pool = p.get(0);
        }
        return true;
    }

    /** At frame start, outside rendering: collects the slot's previous frame and resets it. */
    static void beginFrame(VkCommandBuffer cmd) {
        if (!ready()) return;
        int slot = Drawer.getCurrentFrame() % slots;
        if (written[slot] != 0) collect(slot);
        vkCmdResetQueryPool(cmd, pool, slot * MARKS, MARKS);
        written[slot] = 0;
        mark(cmd, FRAME_START);
    }

    public static void mark(VkCommandBuffer cmd, int id) {
        if (pool == 0L) return;
        int slot = Drawer.getCurrentFrame() % slots;
        if ((written[slot] & (1 << id)) != 0) return;
        written[slot] |= 1 << id;
        VkCompat.writeTimestamp2(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, slot * MARKS + id);
    }

    private static void collect(int slot) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer t = stack.mallocLong(MARKS);
            for (int i = 0; i < MARKS; i++) t.put(i, 0L);
            // Partial results are fine: only written marks are read.
            vkGetQueryPoolResults(Vulkan.getDevice(), pool, slot * MARKS, MARKS, t, 8, VK_QUERY_RESULT_64_BIT);
            long prev = -1;
            for (int i = 0; i < MARKS; i++) {
                if ((written[slot] & (1 << i)) == 0) continue;
                if (prev >= 0) sums[i] += (t.get(i) - prev) * periodNs / 1e6;
                prev = t.get(i);
            }
        }
        if (++frames % LOG_EVERY == 0) {
            StringBuilder b = new StringBuilder("[gpu] ms/frame:");
            double total = 0;
            for (int i = 1; i < MARKS; i++) {
                b.append(' ').append(NAMES[i]).append('=').append(String.format("%.2f", sums[i] / LOG_EVERY));
                total += sums[i];
                sums[i] = 0;
            }
            b.append(" total=").append(String.format("%.2f", total / LOG_EVERY));
            Initializer.LOGGER.info(b.toString());
        }
    }

    static void destroy() {
        if (pool != 0L) vkDestroyQueryPool(Vulkan.getDevice(), pool, null);
        pool = 0L;
    }
}
