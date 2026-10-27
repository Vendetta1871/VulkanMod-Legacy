package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmod.render.VBO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the uploading VBO which section and layer it holds (T4): the ray-tracing scene
 * then places the section right away instead of waiting for its first draw, so geometry
 * outside the view (behind the camera) still casts shadows.
 */
@Mixin(ChunkRenderDispatcher.class)
public class ChunkRenderDispatcherM {

    @Inject(method = "uploadChunk", at = @At("HEAD"))
    private void vulkanmod$beginUpload(BlockRenderLayer layer, BufferBuilder buffer, RenderChunk renderChunk,
                                       CompiledChunk compiledChunk, double distanceSq,
                                       CallbackInfoReturnable<?> cir) {
        VBO.beginSectionUpload(renderChunk.getPosition(), layer);
    }

    @Inject(method = "uploadChunk", at = @At("RETURN"))
    private void vulkanmod$endUpload(BlockRenderLayer layer, BufferBuilder buffer, RenderChunk renderChunk,
                                     CompiledChunk compiledChunk, double distanceSq,
                                     CallbackInfoReturnable<?> cir) {
        VBO.endSectionUpload();
    }
}
