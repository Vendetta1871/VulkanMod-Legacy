package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the fixed-function alpha test (GREATER 0.1) so the GUI shaders know whether
 * to discard transparent fragments. The state is mirrored from the vanilla
 * {@link GlStateManager} calls rather than queried from GL: the Kirino GL layer may
 * not answer {@code glGetBooleanv(GL_ALPHA_TEST)}, and every 1.12.2 render path
 * toggles the test through this class anyway.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerAlphaM {

    @Inject(method = "enableAlpha", at = @At("HEAD"))
    private static void vulkanmod$enableAlpha(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.setAlphaTest(true);
        }
    }

    @Inject(method = "disableAlpha", at = @At("HEAD"))
    private static void vulkanmod$disableAlpha(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.setAlphaTest(false);
        }
    }

    @Inject(method = "alphaFunc(IF)V", at = @At("HEAD"))
    private static void vulkanmod$alphaFunc(int func, float ref, CallbackInfo ci) {
        if (!Initializer.isVulkanActive()) {
            return;
        }
        // The GUI shaders hardcode GREATER 0.1; anything else (ALWAYS, or a ref that
        // discards everything) turns the discard off. The entity shaders consume the
        // live func/ref through getAlphaRef() instead.
        VRenderSystem.setAlphaFunc(func, ref);
        VRenderSystem.setAlphaTest(func == 516 && ref <= 0.1f);
    }
}
