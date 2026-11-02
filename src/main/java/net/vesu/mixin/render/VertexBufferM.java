package net.vesu.mixin.render;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.vesu.Initializer;
import net.vesu.render.VBO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Routes the GL buffer wrapper of 1.12.2 to Vulkan. Blueprint: the original
 * Vesu VertexBufferM for 1.18's {@code client.gl.VertexBuffer}; the chunk
 * upload ({@code bufferData}) becomes a Vulkan vertex buffer upload and the draw
 * goes through the terrain pipeline.
 *
 * <p>While the Vulkan renderer is not active (during startup, or when its
 * initialization failed) the original OpenGL path is kept.</p>
 *
 * <p>The vanilla methods are intercepted with cancellable {@code @Inject}s rather than
 * {@code @Overwrite}s: an overwrite must match the runtime (SRG) name of the target,
 * which the build cannot remap, and it would also replace any other mod's injection
 * into the same method. Only the chunk (BLOCK) format is routed to Vulkan; the sky and
 * star buffers keep their GL upload until ported.</p>
 */
@Mixin(net.minecraft.client.renderer.vertex.VertexBuffer.class)
public abstract class VertexBufferM implements net.vesu.render.IVboHolder {

    private VBO vesu$vbo;

    @Override
    public VBO vesu$getVbo() {
        return this.vesu$vbo;
    }

    // Accessor instead of @Shadow: shadowed fields never reach the refmap and would
    // not bind under the SRG runtime names.
    @Accessor("vertexFormat")
    abstract VertexFormat vesu$getVertexFormat();

    @Inject(method = "bufferData(Ljava/nio/ByteBuffer;)V", at = @At("HEAD"), cancellable = true)
    private void vesu$bufferData(ByteBuffer buffer, CallbackInfo ci) {
        if (!Initializer.isVulkanActive() || vesu$getVertexFormat() != DefaultVertexFormats.BLOCK) {
            return;
        }
        if (this.vesu$vbo == null) {
            this.vesu$vbo = new VBO();
        }
        VertexFormat format = vesu$getVertexFormat();
        this.vesu$vbo.upload(buffer, format, buffer.limit() / format.getSize());
        ci.cancel();
    }

    @Inject(method = "drawArrays(I)V", at = @At("HEAD"), cancellable = true)
    private void vesu$drawArrays(int mode, CallbackInfo ci) {
        if (!Initializer.isVulkanActive()) {
            return;
        }
        if (vesu$getVertexFormat() == DefaultVertexFormats.BLOCK && this.vesu$vbo != null) {
            this.vesu$vbo.draw();
        }
        // The sky and star buffers have no Vulkan pipeline: skip their GL draw, they
        // are rendered by SkyRenderer instead.
        ci.cancel();
    }

    @Inject(method = "bindBuffer()V", at = @At("HEAD"), cancellable = true)
    private void vesu$bindBuffer(CallbackInfo ci) {
        // Only the BLOCK buffer is Vulkan-owned. Cancelling the bind for the sky/star
        // (or mod) buffers too left vanilla's GL bufferData writing into whatever buffer
        // happened to be bound - buffer 0 or someone else's (N19).
        if (Initializer.isVulkanActive() && vesu$getVertexFormat() == DefaultVertexFormats.BLOCK) {
            ci.cancel();
        }
    }

    @Inject(method = "unbindBuffer()V", at = @At("HEAD"), cancellable = true)
    private void vesu$unbindBuffer(CallbackInfo ci) {
        if (Initializer.isVulkanActive() && vesu$getVertexFormat() == DefaultVertexFormats.BLOCK) {
            ci.cancel();
        }
    }

    @Inject(method = "deleteGlBuffers()V", at = @At("HEAD"))
    private void vesu$deleteGlBuffers(CallbackInfo ci) {
        if (this.vesu$vbo != null) {
            this.vesu$vbo.close();
            this.vesu$vbo = null;
        }
        // Vanilla then deletes the GL buffer id it still owns.
    }
}
