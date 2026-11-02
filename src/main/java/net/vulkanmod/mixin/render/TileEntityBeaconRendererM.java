package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntityBeaconRenderer;
import net.vulkanmod.shader.PackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code beacon.beam.depth=true}: the translucent part of the beam writes depth. */
@Mixin(TileEntityBeaconRenderer.class)
public class TileEntityBeaconRendererM {

    @Redirect(method = "renderBeamSegment(DDDDDDII[FDD)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlStateManager;depthMask(Z)V"), require = 0)
    private static void vulkanmod$beamDepth(boolean flag) {
        GlStateManager.depthMask(flag || PackManager.rendererIsTrue("beacon.beam.depth"));
    }
}
