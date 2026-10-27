package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors the GL_NV_fog_distance mode (R16). Vanilla's {@code EntityRenderer.setupFog}
 * calls {@code glFogi(GL_FOG_DISTANCE_MODE_NV 0x855A, GL_EYE_RADIAL_NV 0x855B)} in
 * both linear-fog branches whenever the extension is present, so the driver then
 * measures fog by radial distance while the port's shaders kept using |z| (the
 * metric of contexts without the extension). Only that pname is tracked; the mode
 * is never reset by vanilla, so it is never reset here either.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerFogM {

    @Inject(method = "glFogi(II)V", at = @At("HEAD"))
    private static void vulkanmod$glFogi(int pname, int param, CallbackInfo ci) {
        if (pname == 0x855A && Initializer.isVulkanActive()) {
            VRenderSystem.setFogDistanceMode(param);
        }
    }
}
