package net.vesu.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.vesu.Initializer;
import net.vesu.vulkan.Drawer;
import net.vesu.vulkan.FrameTargets;
import net.vesu.vulkan.texture.VulkanImage;

import java.util.HashMap;

/**
 * Mod-owned GL framebuffers drawn during the GUI pass. A mod that renders an icon into a
 * {@link Framebuffer} of its own (Advancement Plaques draws the plaque item into a 96x96
 * FBO, then samples its texture onto the toast) would otherwise have its geometry replayed
 * straight onto the display with the FBO's ortho projection - a full-screen icon - and its
 * texture sampled as an empty GL mirror.
 *
 * <p>Each such framebuffer gets a Vulkan image of its texture's size. While the framebuffer
 * is bound, GUI draws record into that image ({@link Drawer#beginOffscreen}); once it is
 * unbound the image becomes sampleable, and {@code GuiRenderer} uses it wherever the GL
 * texture of the framebuffer is bound. {@code framebufferClear} clears it on its next bind.
 * The images are stored upright like the display; GL keeps row 0 at the bottom, so the
 * sampling side flips V ({@link #isTargetImage}).</p>
 *
 * <p>Only the display phase of a GUI pass is redirected, outside the cached HUD capture and
 * only for framebuffers that fit the display (its depth attachment is reused). Everything
 * else keeps the previous behaviour.</p>
 */
public final class OffscreenTargets {

    private static final class Target {
        VulkanImage image;
        int width, height;
        /** The image holds rendered content in the sampled layout. */
        boolean ready;
        /** framebufferClear ran: the next bind starts from {@link #clearColor}. */
        boolean pendingClear;
        final float[] clearColor = new float[4];
    }

    /** GL texture id of the framebuffer -> its Vulkan target. */
    private static final HashMap<Integer, Target> targets = new HashMap<>();
    private static Target active;
    private static int activeTexture;
    private static int errorLogs;
    private static boolean loggedFirst;

    private OffscreenTargets() {}

    /** {@code Framebuffer.bindFramebuffer} HEAD. */
    public static void onBind(Framebuffer fb) {
        if (fb == Minecraft.getMinecraft().getFramebuffer()) {
            end();
            return;
        }
        if (active != null && activeTexture == fb.framebufferTexture) {
            return;
        }
        end();
        if (!eligible(fb)) {
            return;
        }
        try {
            int w = fb.framebufferTextureWidth, h = fb.framebufferTextureHeight;
            Target t = targets.computeIfAbsent(fb.framebufferTexture, k -> new Target());
            if (t.image == null || t.width != w || t.height != h) {
                if (t.image != null) {
                    t.image.free(); // deferred past the frames in flight
                }
                t.image = VulkanImage.renderTarget(w, h);
                t.width = w;
                t.height = h;
                t.ready = false;
            }
            float[] clear = null;
            if (t.pendingClear) {
                clear = t.clearColor;
            } else if (!t.ready) {
                clear = new float[4]; // nothing to load yet: start transparent
            }
            if (!Drawer.beginOffscreen(t.image, Math.min(fb.framebufferWidth, w), Math.min(fb.framebufferHeight, h), clear)) {
                return;
            }
            t.pendingClear = false;
            t.ready = true;
            active = t;
            activeTexture = fb.framebufferTexture;
            GuiRenderer.onOffscreenChanged(true);
            if (!loggedFirst) {
                loggedFirst = true;
                Initializer.LOGGER.info("[offscreen] first mod framebuffer redirected: tex {} {}x{}", fb.framebufferTexture, w, h);
            }
        } catch (Throwable e) {
            if (errorLogs++ < 3) {
                Initializer.LOGGER.error("[offscreen] bind failed ({}x{})", fb.framebufferTextureWidth, fb.framebufferTextureHeight, e);
            }
        }
    }

    /** {@code Framebuffer.unbindFramebuffer} HEAD. */
    public static void onUnbind(Framebuffer fb) {
        if (active != null && activeTexture == fb.framebufferTexture) {
            end();
        }
    }

    /**
     * {@code Framebuffer.framebufferClear} HEAD. Vanilla binds, clears through GL and
     * unbinds; the clear is carried over to the bind that follows.
     */
    public static void onClear(Framebuffer fb) {
        if (!Initializer.isVulkanActive() || fb == Minecraft.getMinecraft().getFramebuffer() || fb.framebufferTexture <= 0) {
            return;
        }
        Target t = targets.computeIfAbsent(fb.framebufferTexture, k -> new Target());
        System.arraycopy(fb.framebufferColor, 0, t.clearColor, 0, 4);
        t.pendingClear = true;
        if (active == t) {
            end(); // the bind inside framebufferClear restarts it cleared
        }
    }

    /** {@code Framebuffer.deleteFramebuffer} HEAD. */
    public static void onDelete(Framebuffer fb) {
        forget(fb.framebufferTexture);
    }

    /** Drops the target of a deleted framebuffer texture. */
    public static void forget(int texId) {
        if (targets.isEmpty()) {
            return;
        }
        Target t = targets.get(texId);
        if (t == null) {
            return;
        }
        if (active == t) {
            end();
        }
        targets.remove(texId);
        if (t.image != null) {
            t.image.free();
        }
    }

    /** Back to the display: frame end, GUI pass end, cached HUD capture. */
    public static void end() {
        if (active == null) {
            return;
        }
        VulkanImage image = active.image;
        active = null;
        activeTexture = 0;
        try {
            Drawer.endOffscreen(image);
        } finally {
            GuiRenderer.onOffscreenChanged(false);
        }
    }

    public static boolean isActive() {
        return active != null;
    }

    /** The image to sample for GL texture {@code texId}, or null (not a target, or being drawn). */
    public static VulkanImage sampled(int texId) {
        if (targets.isEmpty()) {
            return null;
        }
        Target t = targets.get(texId);
        return t != null && t.ready && t != active ? t.image : null;
    }

    /** True for an image owned by a target: its V axis is flipped against GL. */
    public static boolean isTargetImage(VulkanImage image) {
        if (image == null || targets.isEmpty()) {
            return false;
        }
        for (Target t : targets.values()) {
            if (t.image == image) {
                return true;
            }
        }
        return false;
    }

    private static boolean eligible(Framebuffer fb) {
        return Initializer.isVulkanActive() && GuiRenderer.isGuiPass() && Drawer.isActive()
                && FrameTargets.phase() == FrameTargets.Phase.DISPLAY && !HudCache.capturing()
                && fb.framebufferTexture > 0 && fb.framebufferTextureWidth > 0 && fb.framebufferTextureHeight > 0
                && fb.framebufferTextureWidth <= FrameTargets.currentWidth()
                && fb.framebufferTextureHeight <= FrameTargets.currentHeight();
    }
}
