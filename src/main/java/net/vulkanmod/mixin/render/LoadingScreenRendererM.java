package net.vulkanmod.mixin.render;

import net.minecraft.client.LoadingScreenRenderer;
import net.vulkanmod.Initializer;
import net.vulkanmod.debug.AutoTest;
import net.vulkanmod.render.GuiRenderer;
import net.vulkanmod.vulkan.Drawer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/**
 * Renders the world-loading screen through the Vulkan GUI path.
 *
 * <p>{@code LoadingScreenRenderer} is not a {@code GuiScreen}: it draws the tiled dirt
 * background, the progress bar and the status text itself, in a synchronous loop driven by
 * {@code Minecraft.launchIntegratedServer}, into its own framebuffer, and presents by
 * calling {@code Minecraft.updateDisplay} directly. That puts it outside both GUI-pass
 * triggers - {@code setupOverlayRendering} inside {@code updateCameraAndRender} and the
 * {@code GuiScreen.drawScreen} bracket - so without help its geometry is never captured and
 * the screen shows nothing but the frame's clear colour.</p>
 *
 * <p>Opening the pass for the duration of one {@code setLoadingProgress} call captures
 * exactly that call's own draws and nothing else; the flag is restored on the way out
 * because the call also presents, so no frame is left half-written. The pass itself is
 * opened on demand by {@code GuiRenderer} (its draws are the first thing in a frame that
 * this call's own {@code updateDisplay} then presents).</p>
 */
@Mixin(LoadingScreenRenderer.class)
public class LoadingScreenRendererM {

    private static boolean captured;
    private static int calls;

    @Inject(method = "setLoadingProgress", at = @At("HEAD"))
    private void vulkanmod$beginGuiPass(int progress, CallbackInfo ci) {
        if (!Initializer.isVulkanActive()) {
            return;
        }
        GuiRenderer.setGuiPass(true);

        // Autotest only: capture the loading screen once it has settled. Requesting before
        // the call draws means the grab lands on this call's own presented frame.
        if (AutoTest.ENABLED && !captured && ++calls >= 3) {
            captured = true;
            File out = new File(net.minecraft.client.Minecraft.getMinecraft().gameDir,
                    "vm_autotest_loading.png");
            Drawer.requestCapture(out);
            Initializer.LOGGER.info("[autotest] loading-screen capture requested -> {}", out);
        }
    }

    @Inject(method = "setLoadingProgress", at = @At("TAIL"))
    private void vulkanmod$endGuiPass(int progress, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setGuiPass(false);
        }
    }
}
