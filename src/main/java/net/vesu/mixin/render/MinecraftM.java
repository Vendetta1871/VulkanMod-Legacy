package net.vesu.mixin.render;

import net.minecraft.client.Minecraft;
import net.vesu.Initializer;
import net.vesu.debug.FrameProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the Vulkan frame loop: initializes the renderer once the window exists,
 * presents frames in place of the GL swap and marks the swapchain for recreation
 * on window resize.
 */
@Mixin(Minecraft.class)
public class MinecraftM {

    @Inject(method = "init", at = @At("TAIL"))
    private void vesu$initVulkan(CallbackInfo ci) {
        Initializer.initVulkan();
    }

    /**
     * World load/unload: hand the previous world's chunk-arena ranges back (M22). The
     * two-argument overload is the funnel - the one-argument one delegates to it.
     */
    @Inject(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private void vesu$worldChange(net.minecraft.client.multiplayer.WorldClient world,
                                       String loadedInWorldPos, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            net.vesu.render.ChunkBatcher.onWorldChange();
        }
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void vesu$frameBegin(CallbackInfo ci) {
        FrameProfiler.frameBegin();
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void vesu$presentFrame(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            Minecraft mc = (Minecraft) (Object) this;
            // updateDisplay() normally runs checkWindowResize() right after the GL swap we replace.
            // Cancelling the whole method would skip it, leaving displayWidth/Height and the open
            // screen's scaled size stale, so GUI buttons stop matching the cursor after a window
            // resize (and only line up again when the window is dragged back to its old size).
            // Do the same bookkeeping - resize() updates the size, the current screen and the
            // framebuffer - before presenting through Vulkan.
            int width = org.lwjgl.opengl.Display.getWidth();
            int height = org.lwjgl.opengl.Display.getHeight();
            if (width > 0 && height > 0 && (width != mc.displayWidth || height != mc.displayHeight)) {
                mc.resize(width, height);
            }
            FrameProfiler.presentBegin();
            Initializer.presentFrame();
            FrameProfiler.presentEnd();
            ci.cancel();
        } else {
            // Keep retrying until the window exists; vanilla GL presents meanwhile.
            Initializer.initVulkan();
        }
    }

    /**
     * The vanilla video settings become {@link net.vesu.config.gui.VesuOptionsScreen} while the Vulkan
     * renderer runs, whoever opens them (the options menu or another mod).
     */
    @ModifyVariable(method = "displayGuiScreen", at = @At("HEAD"), argsOnly = true)
    private net.minecraft.client.gui.GuiScreen vesu$videoSettings(net.minecraft.client.gui.GuiScreen screen) {
        if (screen instanceof net.minecraft.client.gui.GuiVideoSettings && Initializer.isVulkanActive()) {
            return new net.vesu.config.gui.VesuOptionsScreen(((GuiVideoSettingsAccessor) screen).vesu$getParent());
        }
        return screen;
    }

    /**
     * The menu frame rate (Vesu setting) while the game is paused, on the title menus or
     * hidden; vanilla only had a fixed 30 on the title menus.
     */
    @Inject(method = "getLimitFramerate", at = @At("HEAD"), cancellable = true)
    private void vesu$menuFramerate(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        boolean menu = (mc.world == null && mc.currentScreen != null) || mc.isGamePaused()
                || !org.lwjgl.opengl.Display.isVisible();
        if (menu && Initializer.CONFIG != null) {
            cir.setReturnValue(Math.min(Initializer.CONFIG.menuFramerate, mc.gameSettings.limitFramerate));
        }
    }

    @Inject(method = "updateFramebufferSize", at = @At("HEAD"))
    private void vesu$onResize(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        Initializer.onFramebufferSize(mc.displayWidth, mc.displayHeight);
    }
}
