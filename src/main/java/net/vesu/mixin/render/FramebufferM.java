package net.vesu.mixin.render;

import net.minecraft.client.shader.Framebuffer;
import net.vesu.Initializer;
import net.vesu.render.GuiRenderer;
import net.vesu.render.OffscreenTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the framebuffer blit quad from the GUI capture. Under Kirino the world
 * renders into an FBO which vanilla stretches over the screen with a full-screen
 * POSITION_TEX_COLOR quad ({@code framebufferRender}); that quad is the final composited
 * image, not GUI content, and replaying it in Vulkan (whose swapchain is presented
 * instead of Kirino's) covers the frame in black.
 */
@Mixin(Framebuffer.class)
public class FramebufferM {

    @Inject(method = "framebufferRender(II)V", at = @At("HEAD"))
    private void vesu$beginBlit(int width, int height, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setSuppressDraw(true);
        }
    }

    @Inject(method = "framebufferRender(II)V", at = @At("TAIL"))
    private void vesu$endBlit(int width, int height, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setSuppressDraw(false);
        }
    }

    // Mod framebuffers drawn in the GUI pass render into Vulkan images (OffscreenTargets).

    @Inject(method = "bindFramebuffer(Z)V", at = @At("HEAD"))
    private void vesu$bind(boolean viewport, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            OffscreenTargets.onBind((Framebuffer) (Object) this);
        }
    }

    @Inject(method = "unbindFramebuffer()V", at = @At("HEAD"))
    private void vesu$unbind(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            OffscreenTargets.onUnbind((Framebuffer) (Object) this);
        }
    }

    @Inject(method = "framebufferClear()V", at = @At("HEAD"))
    private void vesu$clear(CallbackInfo ci) {
        OffscreenTargets.onClear((Framebuffer) (Object) this);
    }

    @Inject(method = "deleteFramebuffer()V", at = @At("HEAD"))
    private void vesu$delete(CallbackInfo ci) {
        OffscreenTargets.onDelete((Framebuffer) (Object) this);
    }
}
