package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.tileentity.TileEntityEndPortalRenderer;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.EndPortalRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the capture window of {@link EndPortalRenderer} around the End portal TESR.
 * The renderer draws its planes as {@code POSITION_COLOR} quads whose texture
 * coordinates come from eye-linear texgen the port cannot replay through GL, so the
 * uploader hook must recognize its buffers and route them to the CPU-UV path.
 */
@Mixin(TileEntityEndPortalRenderer.class)
public abstract class TileEntityEndPortalRendererM {

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntityEndPortal;DDDFIF)V",
            at = @At("HEAD"))
    private void vulkanmod$beginPortal(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            EndPortalRenderer.setActive(true);
        }
    }

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntityEndPortal;DDDFIF)V",
            at = @At("RETURN"))
    private void vulkanmod$endPortal(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            EndPortalRenderer.setActive(false);
        }
    }
}
