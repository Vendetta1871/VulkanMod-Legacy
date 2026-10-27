package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors the immediate-mode vertex stream into {@link GuiRenderer}. {@code FontRenderer}
 * draws each font glyph with {@code glBegin(GL_TRIANGLE_STRIP)} / {@code glTexCoord2f} /
 * {@code glVertex3f} / {@code glEnd}, which never touches the {@code BufferBuilder}, so
 * the {@code WorldVertexBufferUploader} hook cannot see text. GuiRenderer accumulates
 * during a GUI pass and during the entity pass (world-space name tags and sign text);
 * immediate-mode use in other passes is left untouched.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerImmediateM {

    // N28: redirects of the GL11 call inside each method - no CallbackInfo per glyph vertex.
    @Redirect(method = "glBegin(I)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBegin(I)V", remap = false))
    private static void vulkanmod$begin(int mode) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.immediateBegin(mode);
        }
        GL11.glBegin(mode);
    }

    @Redirect(method = "glTexCoord2f(FF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexCoord2f(FF)V", remap = false))
    private static void vulkanmod$texCoord(float u, float v) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.immediateTexCoord(u, v);
        }
        GL11.glTexCoord2f(u, v);
    }

    @Redirect(method = "glVertex3f(FFF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glVertex3f(FFF)V", remap = false))
    private static void vulkanmod$vertex(float x, float y, float z) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.immediateVertex(x, y, z);
        }
        GL11.glVertex3f(x, y, z);
    }

    @Redirect(method = "glEnd()V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnd()V", remap = false))
    private static void vulkanmod$end() {
        GL11.glEnd(); // the old hook ran at TAIL, i.e. after the GL call
        if (Initializer.isVulkanActive()) {
            GuiRenderer.immediateEnd();
        }
    }

    /**
     * Tracks {@code glLineWidth} so the debug-line capture can feed it to the line
     * pipeline's dynamic line-width state. Kirino does not answer
     * {@code glGetFloatv(GL_LINE_WIDTH)}, so the value is mirrored from the setter like
     * the blend state; only line-topology draws consume it.
     */
    @Inject(method = "glLineWidth(F)V", at = @At("HEAD"))
    private static void vulkanmod$lineWidth(float width, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.setLineWidth(width);
        }
    }
}
