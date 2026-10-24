package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors the fixed-function light positions into the shader light buffers. The item
 * pipelines shade with the two standard-item lights, but each caller positions them
 * with a different transform on the matrix stack (GUI: -30 deg rotation, hand: the
 * player look rotations, world: the pure camera rotation), so the direction has to be
 * captured at the {@code glLight} call itself.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerLightM {

    /** GL_POSITION and the two light enums; the item lighting only ever uses light 0/1. */
    private static final int GL_POSITION = 4611;
    private static final int GL_LIGHT0 = 16384;
    private static final int GL_LIGHT1 = 16385;

    @Inject(method = "glLight(IILjava/nio/FloatBuffer;)V", at = @At("HEAD"))
    private static void vulkanmod$glLightPosition(int light, int pname, java.nio.FloatBuffer param,
                                                  CallbackInfo ci) {
        if (Initializer.isVulkanActive() && pname == GL_POSITION
                && (light == GL_LIGHT0 || light == GL_LIGHT1)) {
            VRenderSystem.mirrorLightPosition(light, param);
        }
    }
}
