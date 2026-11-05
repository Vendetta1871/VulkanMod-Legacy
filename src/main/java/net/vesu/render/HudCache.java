package net.vesu.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.vesu.Initializer;
import net.vesu.mixin.render.GuiIngameForgeInvoker;
import net.vesu.vulkan.Drawer;
import net.vesu.vulkan.FrameTargets;
import net.vesu.vulkan.Vulkan;
import net.vesu.vulkan.texture.VulkanImage;

/**
 * HUD caching (setting {@code hudFramerate}), after Gnetum. The in-game overlay (hotbar,
 * health, chat, scoreboard, F3, mod overlays) changes a few times a second but costs its
 * tessellation every frame. It is drawn into an image of its own at most {@code hudFramerate}
 * times a second; every other frame skips {@code renderGameOverlay} and draws that image as one
 * quad.
 *
 * <p>The image holds premultiplied color: while it is drawn, the alpha channel blends with
 * (ONE, ONE_MINUS_SRC_ALPHA) whatever the color factors are ({@link #alphaOver()}), so
 * translucent layers stack like they do over the world, and the quad goes on with
 * (ONE, ONE_MINUS_SRC_ALPHA). Two elements blend with the picture under them and cannot be
 * cached: the vignette (multiplies the world) and the crosshair (inverts it). They are left out
 * of the image ({@code GuiIngameForgeM}) and drawn every frame, the vignette under the image and
 * the crosshair over it.</p>
 *
 * <p>Gnetum spreads the elements over several frames; here the whole overlay is redrawn in one
 * frame, which keeps every update consistent and needs no per-element bookkeeping. The image
 * is drawn with the display's color and depth formats, so the GUI pipelines render into it
 * unchanged.</p>
 */
public final class HudCache {

    private static VulkanImage image;
    private static int imageW, imageH;
    private static long lastUpdate;
    private static boolean capturing, valid;
    private static int updates;
    private static long updatesSince;
    private static float measuredFps;

    private HudCache() {}

    public static void init() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(HudCache.class);
    }

    /** True while the overlay is drawn into the image (blend and element hooks). */
    public static boolean capturing() {
        return capturing;
    }

    /** While capturing, the alpha factors of every blended draw (see the class doc). */
    public static boolean alphaOver() {
        return capturing;
    }

    /**
     * Replaces {@code mc.ingameGUI.renderGameOverlay(partialTicks)} in
     * {@code updateCameraAndRender}, after the GUI pass and the display phase have begun.
     */
    public static void renderOverlay(GuiIngame gui, float partialTicks) {
        int rate = Initializer.CONFIG.hudFramerate;
        if (rate <= 0 || !(gui instanceof GuiIngameForge forge) || !Initializer.isVulkanActive()
                || FrameTargets.phase() != FrameTargets.Phase.DISPLAY) {
            valid = false;
            gui.renderGameOverlay(partialTicks);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // The display target, not mc.displayWidth: the two differ for a while after a resize.
        int w = FrameTargets.currentWidth(), h = FrameTargets.currentHeight();
        if (image == null || imageW != w || imageH != h) {
            if (image != null) {
                Vulkan.waitIdle();
                image.free();
            }
            image = VulkanImage.renderTarget(w, h);
            imageW = w;
            imageH = h;
            valid = false;
        }

        GuiIngameForgeInvoker invoker = (GuiIngameForgeInvoker) forge;
        long now = System.nanoTime();
        boolean update = !valid || now - lastUpdate >= 1_000_000_000L / rate;
        if (update) {
            // Draw on the rate's grid so the HUD keeps its frame rate when frames come unevenly.
            lastUpdate = valid && now - lastUpdate < 2_000_000_000L / rate ? lastUpdate + 1_000_000_000L / rate : now;
            Drawer.beginHudCapture(image);
            capturing = true;
            try {
                gui.renderGameOverlay(partialTicks);
            } finally {
                capturing = false;
                Drawer.endHudCapture(image);
            }
            valid = true;
            countUpdate(now);
        }

        // Every frame: the vignette under the image, the crosshair over it.
        ScaledResolution res = forge.getResolution();
        if (res == null) res = new ScaledResolution(mc);
        if (GuiIngameForge.renderVignette && Minecraft.isFancyGraphicsEnabled() && mc.getRenderViewEntity() != null) {
            GlStateManager.enableBlend();
            invoker.vesu$renderVignette(mc.getRenderViewEntity().getBrightness(), res);
        }
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlpha();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GuiRenderer.drawFullscreen(image, res.getScaledWidth_double(), res.getScaledHeight_double());
        GlStateManager.enableAlpha();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        if (GuiIngameForge.renderCrosshairs) {
            invoker.vesu$renderCrosshairs(partialTicks);
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void countUpdate(long now) {
        updates++;
        if (now - updatesSince >= 1_000_000_000L) {
            measuredFps = updates * 1.0e9f / Math.max(1L, now - updatesSince);
            updates = 0;
            updatesSince = now;
        }
    }

    @SubscribeEvent
    public static void onDebugText(RenderGameOverlayEvent.Text e) {
        if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) return;
        int rate = Initializer.CONFIG.hudFramerate;
        e.getLeft().add(rate <= 0 || !valid ? "HUD: every frame" : String.format("HUD: %.0f fps (max %d)", measuredFps, rate));
    }
}
