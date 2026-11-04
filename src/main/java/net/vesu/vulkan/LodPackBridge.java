package net.vesu.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * What the LOD renderer needs from the pack run this frame (AUDIT 5.16, contract B): the
 * pack's DH programs, its attachments and shared sets, and the pass that draws distant
 * terrain into the pack's targets with its own depth ({@code dhDepthTex0}).
 */
public final class LodPackBridge {
    private LodPackBridge() {
    }

    /** The pack of this frame draws distant terrain with its own programs; LODs go through them. */
    public static boolean active() {
        PackRuntime p = FrameTargets.packRuntime();
        return p != null && FrameTargets.phase() == FrameTargets.Phase.SCENE && FrameTargets.geometry() != null
                && p.lodProgram(false) != null;
    }

    /** Changes whenever the pack runtime is rebuilt: pipelines made from it must be made again. */
    public static Object token() {
        return FrameTargets.packRuntime();
    }

    /** {@code dh_terrain} (false) or {@code dh_water} (true), or null. */
    public static LodPackProgram program(boolean translucent) {
        return FrameTargets.packRuntime().lodProgram(translucent);
    }

    public static int[] colorFormats() {
        return FrameTargets.packRuntime().attachmentFormats();
    }

    public static long sharedSetLayout() {
        return FrameTargets.packRuntime().geometrySetLayout();
    }

    /** Set 2 of the pack's programs, or 0. */
    public static long resourceSetLayout() {
        return FrameTargets.packRuntime().resourceSetLayout();
    }

    public static long sharedSet() {
        return FrameTargets.packRuntime().geometrySet();
    }

    public static void bindResources(VkCommandBuffer cmd, long layout) {
        FrameTargets.packRuntime().bindResources(cmd, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layout);
    }

    /**
     * Leaves scene rendering and renders the pack's targets with the distant terrain's depth.
     * Before distant water, dhDepthTex1 takes a copy of that depth first.
     */
    public static void begin(VkCommandBuffer cmd, MemoryStack stack, boolean translucent) {
        FrameTargets.beginLodPackPass(cmd, stack, translucent);
    }

    /** Back to scene rendering, everything loaded; dhDepthTex0 is readable. */
    public static void end(VkCommandBuffer cmd, MemoryStack stack) {
        FrameTargets.endLodPackPass(cmd, stack);
    }

    public static final int DEPTH_FORMAT = org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
}
