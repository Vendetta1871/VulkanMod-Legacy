package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.VBO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.ByteBuffer;

/**
 * Routes the GL buffer wrapper of 1.12.2 to Vulkan. Blueprint: the original
 * VulkanMod VertexBufferM for 1.18's {@code client.gl.VertexBuffer}; the chunk
 * upload ({@code bufferData}) becomes a Vulkan vertex buffer upload and the draw
 * goes through the terrain pipeline.
 *
 * <p>While the Vulkan renderer is not active (during startup, or when its
 * initialization failed) the original OpenGL path is kept.</p>
 */
@Mixin(net.minecraft.client.renderer.vertex.VertexBuffer.class)
public class VertexBufferM {

    @Shadow
    private int glBufferId;

    @Shadow
    private int count;

    @Shadow
    private VertexFormat vertexFormat;

    private VBO vulkanmod$vbo;

    /**
     * @author
     */
    @Overwrite
    public void bufferData(ByteBuffer buffer) {
        int vertexCount = buffer.limit() / this.vertexFormat.getSize();

        if (Initializer.isVulkanActive()) {
            if (this.vulkanmod$vbo == null) {
                this.vulkanmod$vbo = new VBO();
            }
            this.vulkanmod$vbo.upload(buffer, this.vertexFormat, vertexCount);
        } else {
            // Renderer not up yet (e.g. sky/star buffers created in Minecraft.init)
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, this.glBufferId);
            OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, buffer, 35044); // GL_STATIC_DRAW
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }

        this.count = vertexCount;
    }

    /**
     * @author
     */
    @Overwrite
    public void drawArrays(int mode) {
        if (!Initializer.isVulkanActive()) {
            GlStateManager.glDrawArrays(mode, 0, this.count);
            return;
        }

        // Only the chunk (BLOCK) format has a Vulkan pipeline so far; the sky and
        // star buffers keep their GL data and stay invisible until ported.
        if (this.vertexFormat == DefaultVertexFormats.BLOCK && this.vulkanmod$vbo != null) {
            this.vulkanmod$vbo.draw();
        }
    }

    /**
     * @author
     */
    @Overwrite
    public void bindBuffer() {
        if (!Initializer.isVulkanActive()) {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, this.glBufferId);
        }
    }

    /**
     * @author
     */
    @Overwrite
    public void unbindBuffer() {
        if (!Initializer.isVulkanActive()) {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }
    }

    /**
     * @author
     */
    @Overwrite
    public void deleteGlBuffers() {
        if (this.vulkanmod$vbo != null) {
            this.vulkanmod$vbo.close();
            this.vulkanmod$vbo = null;
        }
        if (this.glBufferId >= 0) {
            OpenGlHelper.glDeleteBuffers(this.glBufferId);
            this.glBufferId = -1;
        }
    }
}
