package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.RenderGlobal;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.CloudRenderer;
import net.vulkanmod.render.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Vulkan sky and clouds at the head of {@code RenderGlobal.renderSky} and
 * {@code renderClouds}. The GL sky and clouds keep running afterwards (their output is
 * never presented) so the GL state machine stays consistent for everything that renders
 * after them.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalM {

    @Shadow
    private int cloudTickCounter;

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"))
    private void vulkanmod$renderSky(float partialTicks, int pass, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            SkyRenderer.render(partialTicks, pass);
        }
    }

    @Inject(method = "renderClouds(FIDDD)V", at = @At("HEAD"))
    private void vulkanmod$renderClouds(float partialTicks, int pass, double x, double y, double z,
                                        CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            CloudRenderer.render(partialTicks, pass, this.cloudTickCounter, x, y, z);
        }
    }
}
