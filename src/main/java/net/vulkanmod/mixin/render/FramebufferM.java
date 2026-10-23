package net.vulkanmod.mixin.render;

import net.minecraft.client.shader.Framebuffer;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
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
    private void vulkanmod$beginBlit(int width, int height, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setSuppressDraw(true);
        }
    }

    @Inject(method = "framebufferRender(II)V", at = @At("TAIL"))
    private void vulkanmod$endBlit(int width, int height, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setSuppressDraw(false);
        }
    }
}
