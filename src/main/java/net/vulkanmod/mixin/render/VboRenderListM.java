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

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$onChunkLayerStart(BlockRenderLayer layer, CallbackInfo ci) {
        // Diagnostic: skip the entire per-chunk pass (loop + draws) to bound terrain cost.
        if (TerrainRenderer.NO_TERRAIN) {
            ci.cancel();
            return;
        }
        TerrainRenderer.onChunkLayerStart(layer);
    }

    @Inject(method = "renderChunkLayer", at = @At("TAIL"))
    private void vulkanmod$onChunkLayerEnd(BlockRenderLayer layer, CallbackInfo ci) {
        TerrainRenderer.onChunkLayerEnd(layer);
    }

    /**
     * In the batched path the real draw is the per-layer indirect call, which binds the arena
     * and index buffer itself; the per-chunk GL attribute pointers are never read, so skip
     * setting them (they are pure overhead through the GL layer here).
     */
    @Inject(method = "setupArrayPointers", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$skipArrayPointers(CallbackInfo ci) {
        if (TerrainRenderer.BATCH) {
            ci.cancel();
        }
    }
}
