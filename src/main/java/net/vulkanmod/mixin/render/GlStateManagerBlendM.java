package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the blend enable bit and factors from the vanilla {@link GlStateManager}
 * calls. The Kirino GL layer does not answer {@code glGetBooleanv(GL_BLEND)}
 * (measured: false while the HUD was mid-blend), so the GUI pass cannot mirror
 * blending by querying GL; every 1.12.2 render path toggles it through this class
 * anyway. VRenderSystem already stores the state for {@code getBlendState()}.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerBlendM {

    @Inject(method = "enableBlend", at = @At("HEAD"))
    private static void vulkanmod$enableBlend(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.enableBlend();
        }
    }

    @Inject(method = "disableBlend", at = @At("HEAD"))
    private static void vulkanmod$disableBlend(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.disableBlend();
        }
    }

    @Inject(method = "tryBlendFuncSeparate(IIII)V", at = @At("HEAD"))
    private static void vulkanmod$blendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }
    }

    @Inject(method = "color(FFF)V", at = @At("HEAD"))
    private static void vulkanmod$color3(float r, float g, float b, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.trackedColorR = r;
            VRenderSystem.trackedColorG = g;
            VRenderSystem.trackedColorB = b;
            VRenderSystem.trackedColorA = 1;
        }
    }

    @Inject(method = "color(FFFF)V", at = @At("HEAD"))
    private static void vulkanmod$color4(float r, float g, float b, float a, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.trackedColorR = r;
            VRenderSystem.trackedColorG = g;
            VRenderSystem.trackedColorB = b;
            VRenderSystem.trackedColorA = a;
        }
    }
}
