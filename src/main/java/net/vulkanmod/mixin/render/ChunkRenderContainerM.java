package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.vulkanmod.render.IRenderChunks;
import net.vulkanmod.render.TerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Captures the per-chunk transform inputs (section origin and camera position) as vanilla
 * applies them, so the batched path can build the model-view matrix on the CPU instead of
 * querying the GL matrix stack for every section.
 */
@Mixin(ChunkRenderContainer.class)
public abstract class ChunkRenderContainerM implements IRenderChunks {

    // Accessors instead of @Shadow: shadowed fields never reach the refmap and would
    // not bind under the SRG runtime names.
    @Accessor("viewEntityX")
    abstract double vulkanmod$getViewEntityX();

    @Accessor("viewEntityY")
    abstract double vulkanmod$getViewEntityY();

    @Accessor("viewEntityZ")
    abstract double vulkanmod$getViewEntityZ();

    // The visible-chunk list (private on this class); exposed through IRenderChunks so
    // the VboRenderList mixin can clear it when it cancels renderChunkLayer.
    @Override
    @Accessor("renderChunks")
    public abstract List<RenderChunk> vulkanmod$getRenderChunks();

    @Inject(method = "preRenderChunk", at = @At("HEAD"))
    private void vulkanmod$stashTransform(RenderChunk renderChunk, CallbackInfo ci) {
        BlockPos pos = renderChunk.getPosition();
        TerrainRenderer.stashChunkTransform(pos.getX(), pos.getY(), pos.getZ(),
                vulkanmod$getViewEntityX(), vulkanmod$getViewEntityY(), vulkanmod$getViewEntityZ());
    }

    // preRenderChunk is just GlStateManager.translate(sectionOrigin - camera). The batched draw
    // derives the section matrix from the stash above, never the GL matrix stack, so skip the
    // translate (the stash hook reads the position/camera fields directly, not this call).
    @Redirect(method = "preRenderChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V"))
    private void vulkanmod$skipTranslate(float x, float y, float z) {
        if (!TerrainRenderer.BATCH) {
            GlStateManager.translate(x, y, z);
        }
    }
}
