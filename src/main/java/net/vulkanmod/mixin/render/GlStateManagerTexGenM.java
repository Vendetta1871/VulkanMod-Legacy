package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Mirrors the eye-linear texture coordinate generation planes into
 * {@link VRenderSystem}. The End portal TESR is the only 1.12.2 renderer that sets
 * {@code GL_EYE_PLANE} coefficients ({@code texGen(TexGen, 9474, FloatBuffer)}); its
 * Vulkan replay ({@code EndPortalRenderer}) computes the generated coordinates on the
 * CPU from these mirrored planes, because Kirino never runs the fixed-function texgen.
 *
 * <p>The coefficients are stored as specified, without GL's {@code (M⁻¹)ᵀ} transform.
 * The portal TESR specifies the planes under the same modelview it draws with, so the
 * transform cancels and the replay evaluates the raw planes on the object position (R4);
 * a caller that specifies planes under one modelview and draws under another would need
 * the transform mirrored here instead.</p>
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerTexGenM {

    @Inject(method = "texGen(Lnet/minecraft/client/renderer/GlStateManager$TexGen;ILjava/nio/FloatBuffer;)V",
            at = @At("HEAD"))
    private static void vulkanmod$mirrorTexGenPlane(GlStateManager.TexGen texgen, int pname,
                                                    FloatBuffer param, CallbackInfo ci) {
        if (pname != 9474 /* GL_EYE_PLANE */ || !Initializer.isVulkanActive()) {
            return;
        }
        float[] dst;
        if (texgen == GlStateManager.TexGen.S) {
            dst = VRenderSystem.texGenPlaneS;
        } else if (texgen == GlStateManager.TexGen.T) {
            dst = VRenderSystem.texGenPlaneT;
        } else if (texgen == GlStateManager.TexGen.R) {
            dst = VRenderSystem.texGenPlaneR;
        } else {
            return; // Q is never used by the portal renderer
        }
        int p = param.position();
        for (int i = 0; i < 4; i++) {
            dst[i] = param.get(p + i);
        }
    }
}
