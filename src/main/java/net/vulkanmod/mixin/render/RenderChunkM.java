package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.vulkanmod.render.IVboHolder;
import net.vulkanmod.render.VBO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A repositioned render chunk keeps its old geometry until it is rebuilt, but vanilla no
 * longer draws it; the ray-tracing scene must drop it too (T4).
 */
@Mixin(RenderChunk.class)
public abstract class RenderChunkM {

    @Inject(method = "setPosition(III)V", at = @At("HEAD"))
    private void vulkanmod$setPosition(int x, int y, int z, CallbackInfo ci) {
        RenderChunk self = (RenderChunk) (Object) this;
        BlockPos pos = self.getPosition();
        if (pos.getX() == x && pos.getY() == y && pos.getZ() == z) return;
        for (BlockRenderLayer layer : BlockRenderLayer.values()) {
            net.minecraft.client.renderer.vertex.VertexBuffer buffer = self.getVertexBufferByLayer(layer.ordinal());
            if (buffer instanceof IVboHolder holder) {
                VBO vbo = holder.vulkanmod$getVbo();
                if (vbo != null) vbo.unplace();
            }
        }
    }
}
