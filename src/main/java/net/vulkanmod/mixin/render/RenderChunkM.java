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
 * Keeps the ray-tracing scene in step with what vanilla draws (T4): a repositioned render
 * chunk keeps its old geometry until it is rebuilt, and a layer that a rebuild left empty
 * (the cutout layer once its only torch is broken) is never uploaded again; vanilla just
 * stops drawing it. The RT scene drops both, with their light sources.
 */
@Mixin(RenderChunk.class)
public abstract class RenderChunkM {

    @Inject(method = "setCompiledChunk(Lnet/minecraft/client/renderer/chunk/CompiledChunk;)V", at = @At("HEAD"))
    private void vulkanmod$setCompiledChunk(net.minecraft.client.renderer.chunk.CompiledChunk compiled, CallbackInfo ci) {
        if (!net.vulkanmod.vulkan.rt.RtScene.enabled()) return;
        RenderChunk self = (RenderChunk) (Object) this;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        Runnable drop = () -> {
            for (BlockRenderLayer layer : BlockRenderLayer.values()) {
                if (!compiled.isLayerEmpty(layer)) continue;
                net.minecraft.client.renderer.vertex.VertexBuffer buffer = self.getVertexBufferByLayer(layer.ordinal());
                if (buffer instanceof IVboHolder holder) {
                    VBO vbo = holder.vulkanmod$getVbo();
                    if (vbo != null) vbo.unplace();
                }
            }
        };
        // Usually the client thread (upload callback); the RT scene is not thread-safe.
        if (mc.isCallingFromMinecraftThread()) {
            drop.run();
        } else {
            // Skipped if a newer compile replaced this one meanwhile (its layers are current).
            mc.addScheduledTask(() -> {
                if (self.getCompiledChunk() == compiled) drop.run();
            });
        }
    }

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
