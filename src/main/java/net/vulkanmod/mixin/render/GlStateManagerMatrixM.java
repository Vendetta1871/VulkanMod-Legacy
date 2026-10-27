package net.vulkanmod.mixin.render;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Counts matrix mutations (M11/N18) and mirrors the texture matrix (formerly the
 * GlStateManagerBlendM HEAD hooks). N28: these run several thousand times per frame, so
 * they redirect the GL11 call inside each GlStateManager method instead of a HEAD
 * {@code @Inject}: a redirect handler takes no CallbackInfo, so nothing is allocated,
 * and one handler now does both jobs (the old code had two injects per method).
 *
 * <p>rotate(Quaternion) delegates to multMatrix and is covered there. Vanilla GUI code
 * drives every matrix op through GlStateManager; raw GL11 calls from mods slip past the
 * counter (see GuiRenderer, R7).</p>
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMatrixM {

    @Redirect(method = "pushMatrix()V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V", remap = false))
    private static void vulkanmod$push() {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
            VRenderSystem.texMatrixPush();
        }
        GL11.glPushMatrix();
    }

    @Redirect(method = "popMatrix()V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V", remap = false))
    private static void vulkanmod$pop() {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
            VRenderSystem.texMatrixPop();
        }
        GL11.glPopMatrix();
    }

    @Redirect(method = "loadIdentity()V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glLoadIdentity()V", remap = false))
    private static void vulkanmod$loadIdentity() {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
            VRenderSystem.texMatrixLoadIdentity();
        }
        GL11.glLoadIdentity();
    }

    @Redirect(method = "translate(FFF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V", remap = false))
    private static void vulkanmod$translatef(float x, float y, float z) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
            VRenderSystem.texMatrixTranslate(x, y, z);
        }
        GL11.glTranslatef(x, y, z);
    }

    @Redirect(method = "translate(DDD)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslated(DDD)V", remap = false))
    private static void vulkanmod$translated(double x, double y, double z) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
        }
        GL11.glTranslated(x, y, z);
    }

    @Redirect(method = "rotate(FFFF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V", remap = false))
    private static void vulkanmod$rotate(float angle, float x, float y, float z) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
            VRenderSystem.texMatrixRotate(angle, x, y, z);
        }
        GL11.glRotatef(angle, x, y, z);
    }

    @Redirect(method = "scale(FFF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V", remap = false))
    private static void vulkanmod$scalef(float x, float y, float z) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
            VRenderSystem.texMatrixScale(x, y, z);
        }
        GL11.glScalef(x, y, z);
    }

    @Redirect(method = "scale(DDD)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScaled(DDD)V", remap = false))
    private static void vulkanmod$scaled(double x, double y, double z) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
        }
        GL11.glScaled(x, y, z);
    }

    @Redirect(method = "ortho(DDDDDD)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glOrtho(DDDDDD)V", remap = false))
    private static void vulkanmod$ortho(double l, double r, double b, double t, double n, double f) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
        }
        GL11.glOrtho(l, r, b, t, n, f);
    }

    @Redirect(method = "multMatrix(Ljava/nio/FloatBuffer;)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glMultMatrixf(Ljava/nio/FloatBuffer;)V", remap = false))
    private static void vulkanmod$multMatrix(FloatBuffer m) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
        }
        GL11.glMultMatrixf(m);
    }
}
