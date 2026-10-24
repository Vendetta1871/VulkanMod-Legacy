package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmod.debug.FrameProfiler;
import net.vulkanmod.render.TerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
        FrameProfiler.terrainBegin();
        FrameProfiler.layerStartBegin();
        TerrainRenderer.onChunkLayerStart(layer);
        FrameProfiler.layerStartEnd();
    }

    @Inject(method = "renderChunkLayer", at = @At("TAIL"))
    private void vulkanmod$onChunkLayerEnd(BlockRenderLayer layer, CallbackInfo ci) {
        TerrainRenderer.onChunkLayerEnd(layer);
        FrameProfiler.terrainEnd();
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

    // --- batched path: skip the per-chunk model-view setup ---
    // renderChunkLayer wraps each chunk in pushMatrix / preRenderChunk(translate) / popMatrix.
    // The batched draw derives each section's matrix on the CPU from the stashed section origin
    // and camera (see TerrainRenderer.applyCurrentSectionMatrix), so it never reads the GL matrix
    // stack. Skipping the three matrix calls removes ~2 ms/frame of Kirino GL overhead while the
    // loop, frustum culling and the record() call stay exactly as vanilla drives them.

    @Redirect(method = "renderChunkLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;pushMatrix()V"))
    private void vulkanmod$skipPushMatrix() {
        if (!TerrainRenderer.BATCH) {
            GlStateManager.pushMatrix();
        }
    }

    @Redirect(method = "renderChunkLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;popMatrix()V"))
    private void vulkanmod$skipPopMatrix() {
        if (!TerrainRenderer.BATCH) {
            GlStateManager.popMatrix();
        }
    }
}
