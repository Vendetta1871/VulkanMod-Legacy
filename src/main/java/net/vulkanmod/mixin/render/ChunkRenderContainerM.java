package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.vulkanmod.render.TerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the per-chunk transform inputs (section origin and camera position) as vanilla
 * applies them, so the batched path can build the model-view matrix on the CPU instead of
 * querying the GL matrix stack for every section.
 */
@Mixin(ChunkRenderContainer.class)
public class ChunkRenderContainerM {

    @Shadow
    private double viewEntityX;
    @Shadow
    private double viewEntityY;
    @Shadow
    private double viewEntityZ;

    @Inject(method = "preRenderChunk", at = @At("HEAD"))
    private void vulkanmod$stashTransform(RenderChunk renderChunk, CallbackInfo ci) {
        BlockPos pos = renderChunk.getPosition();
        TerrainRenderer.stashChunkTransform(pos.getX(), pos.getY(), pos.getZ(),
                viewEntityX, viewEntityY, viewEntityZ);
    }
}
