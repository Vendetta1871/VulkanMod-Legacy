package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.vulkanmod.vulkan.FrameTargets;
import org.joml.Matrix4f;

/**
 * Camera state of this and the previous frame plus sub-pixel jitter (B7).
 *
 * <p>View-projections are unjittered, already remapped to Vulkan depth, and camera
 * relative: 1.12 renders with a rotation-only model-view and translates geometry by
 * {@code origin - camera}. Reprojecting a point {@code p} (relative to the current
 * camera) into the previous frame is {@code prevViewProj * (p + cam - prevCam)}.</p>
 *
 * <p>Jitter shifts every projection used while the scene phase is active (terrain,
 * sky, clouds, entities), never the hand or the GUI, which render in the display phase.</p>
 */
public final class CameraHistory {

    private static final Matrix4f viewProj = new Matrix4f();
    private static final Matrix4f prevViewProj = new Matrix4f();
    /** Inverse of the main camera's model-view: view space -> camera-relative world axes. */
    private static final Matrix4f viewInverse = new Matrix4f();
    /** The main camera's model-view and unjittered (Vulkan-depth) projection, with last frame's. */
    private static final Matrix4f view = new Matrix4f(), prevView = new Matrix4f();
    private static final Matrix4f proj = new Matrix4f(), prevProj = new Matrix4f();
    private static double camX, camY, camZ;
    private static double prevCamX, prevCamY, prevCamZ;
    private static boolean valid, prevValid;

    /** Temporary switch until a temporal upscaler (F5) turns jitter on. */
    private static boolean jitterEnabled = System.getenv("VULKANMOD_JITTER") != null;
    private static int jitterIndex;
    private static float jitterX, jitterY;

    private CameraHistory() {}

    /** Rotates the history and picks this frame's jitter. Called once at frame start. */
    public static void beginFrame() {
        if (valid) {
            prevViewProj.set(viewProj);
            prevView.set(view);
            prevProj.set(proj);
            prevCamX = camX;
            prevCamY = camY;
            prevCamZ = camZ;
            prevValid = true;
        }
        valid = false;

        if (jitterEnabled) {
            int phases = jitterPhaseCount(FrameTargets.scene.width(), FrameTargets.display.width());
            jitterIndex = (jitterIndex % phases) + 1;
            jitterX = (float) halton(jitterIndex, 2) - 0.5f;
            jitterY = (float) halton(jitterIndex, 3) - 0.5f;
        } else {
            jitterX = jitterY = 0.0f;
        }
    }

    /**
     * Records the main camera of this frame (first terrain layer).
     * @param modelView     the rotation-only camera model-view
     * @param projRemapped  the unjittered projection, remapped to Vulkan depth
     * @param glProjection  the raw GL projection (near/far/FOV are read from it)
     */
    public static void captureMain(Matrix4f modelView, Matrix4f projRemapped, Matrix4f glProjection) {
        if (valid) return;
        projRemapped.mul(modelView, viewProj);
        modelView.invert(viewInverse);
        view.set(modelView);
        proj.set(projRemapped);
        // GL perspective: m22 = -(f+n)/(f-n), m32 = -2fn/(f-n), m11 = cot(fovY/2).
        near = glProjection.m32() / (glProjection.m22() - 1.0f);
        far = glProjection.m32() / (glProjection.m22() + 1.0f);
        fovY = (float) (2.0 * Math.atan(1.0 / glProjection.m11()));

        Minecraft mc = Minecraft.getMinecraft();
        Entity e = mc.getRenderViewEntity();
        if (e != null) {
            double pt = mc.getRenderPartialTicks();
            camX = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
            camY = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt;
            camZ = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
        }
        valid = true;
        if (!logged && prevValid) {
            logged = true;
            net.vulkanmod.Initializer.LOGGER.info("[camera] history active; jitter {} ({} phases)",
                    jitterEnabled ? "on" : "off",
                    jitterPhaseCount(FrameTargets.scene.width(), FrameTargets.display.width()));
        }
    }

    private static boolean logged;

    /** Drops the history: teleport, dimension change, resize, respawn. */
    public static void reset() {
        valid = false;
        prevValid = false;
    }

    private static float near = 0.05f, far = 256.0f, fovY = 1.22f;

    public static boolean hasPrevious() { return prevValid && valid; }
    public static float near() { return near; }
    public static float far() { return far; }
    /** Vertical field of view in radians. */
    public static float fovY() { return fovY; }
    public static Matrix4f viewProj() { return viewProj; }
    public static Matrix4f prevViewProj() { return prevViewProj; }
    public static Matrix4f viewInverse() { return viewInverse; }
    public static Matrix4f view() { return view; }
    public static Matrix4f prevView() { return prevView; }
    public static Matrix4f proj() { return proj; }
    public static Matrix4f prevProj() { return prevProj; }
    /** The main camera of this frame was captured (see captureMain). */
    public static boolean valid() { return valid; }
    public static double camX() { return camX; }
    public static double camY() { return camY; }
    public static double camZ() { return camZ; }
    public static double prevCamX() { return prevCamX; }
    public static double prevCamY() { return prevCamY; }
    public static double prevCamZ() { return prevCamZ; }

    /** Jitter in render-resolution pixels, in [-0.5, 0.5]; +y points down the image (FSR convention). */
    public static float jitterX() { return jitterX; }
    public static float jitterY() { return jitterY; }

    public static void setJitterEnabled(boolean enabled) {
        jitterEnabled = enabled;
    }

    /**
     * Writes the clip-space jitter translation into a post-projection matrix:
     * {@code x' = x + (2 jx / W) w}, {@code y' = y - (2 jy / H) w} (NDC +y is the top row).
     * Applied only in the scene phase.
     */
    public static void applyJitter(Matrix4f clipRemap) {
        if (FrameTargets.phase() != FrameTargets.Phase.SCENE || (jitterX == 0.0f && jitterY == 0.0f)) {
            clipRemap.m30(0.0f).m31(0.0f);
            return;
        }
        clipRemap.m30(2.0f * jitterX / FrameTargets.scene.width());
        clipRemap.m31(-2.0f * jitterY / FrameTargets.scene.height());
    }

    /** Same phase count as FSR (ffxFsr3GetJitterPhaseCount): 8 * (display / render)^2. */
    static int jitterPhaseCount(int renderWidth, int displayWidth) {
        float ratio = renderWidth > 0 ? (float) displayWidth / renderWidth : 1.0f;
        return Math.max(1, (int) (8.0f * ratio * ratio));
    }

    static double halton(int index, int base) {
        double f = 1.0, r = 0.0;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }
}
