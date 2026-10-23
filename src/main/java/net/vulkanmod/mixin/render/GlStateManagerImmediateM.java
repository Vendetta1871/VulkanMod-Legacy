package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors the immediate-mode vertex stream into {@link GuiRenderer}. {@code FontRenderer}
 * draws each font glyph with {@code glBegin(GL_TRIANGLE_STRIP)} / {@code glTexCoord2f} /
 * {@code glVertex3f} / {@code glEnd}, which never touches the {@code BufferBuilder}, so
 * the {@code WorldVertexBufferUploader} hook cannot see text. GuiRenderer only accumulates
 * while a GUI pass is active, so entity/world immediate-mode use is left untouched.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerImmediateM {

    @Inject(method = "glBegin(I)V", at = @At("HEAD"))
    private static void vulkanmod$begin(int mode, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.immediateBegin(mode);
        }
    }

    @Inject(method = "glTexCoord2f(FF)V", at = @At("HEAD"))
    private static void vulkanmod$texCoord(float u, float v, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.immediateTexCoord(u, v);
        }
    }

    @Inject(method = "glVertex3f(FFF)V", at = @At("HEAD"))
    private static void vulkanmod$vertex(float x, float y, float z, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.immediateVertex(x, y, z);
        }
    }

    @Inject(method = "glEnd()V", at = @At("TAIL"))
    private static void vulkanmod$end(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.immediateEnd();
        }
    }
}
