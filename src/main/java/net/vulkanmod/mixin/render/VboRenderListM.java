package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmod.render.TerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors the GL state and refreshes the terrain textures once per chunk layer
 * pass, right before the per-chunk draws of {@link VboRenderList}.
 */
@Mixin(VboRenderList.class)
public class VboRenderListM {

    @Inject(method = "renderChunkLayer", at = @At("HEAD"))
    private void vulkanmod$onChunkLayerStart(BlockRenderLayer layer, CallbackInfo ci) {
        TerrainRenderer.onChunkLayerStart(layer);
    }

    @Inject(method = "renderChunkLayer", at = @At("TAIL"))
    private void vulkanmod$onChunkLayerEnd(BlockRenderLayer layer, CallbackInfo ci) {
        TerrainRenderer.onChunkLayerEnd(layer);
    }
}
