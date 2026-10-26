package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the depth and cull state from the vanilla {@link GlStateManager} calls, the
 * same way {@link GlStateManagerBlendM} tracks blending. The terrain layer pass used
 * to mirror these by querying GL once per frame (at the first layer), so the later
 * layers inherited the first layer's depth write / cull state - the translucent layer
 * wrote depth like the solid one. Every 1.12.2 render path toggles this state through
 * GlStateManager, so tracking it here keeps VRenderSystem correct at every layer
 * without any glGet* round-trips.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerDepthM {

    @Inject(method = "enableDepth", at = @At("HEAD"))
    private static void vulkanmod$enableDepth(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.glDepthTest = true;
            VRenderSystem.enableDepthTest();
        }
    }

    @Inject(method = "disableDepth", at = @At("HEAD"))
    private static void vulkanmod$disableDepth(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.glDepthTest = false;
            VRenderSystem.disableDepthTest();
        }
    }

    @Inject(method = "depthMask(Z)V", at = @At("HEAD"))
    private static void vulkanmod$depthMask(boolean flag, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.glDepthMask = flag;
            VRenderSystem.depthMask(flag);
        }
    }

    @Inject(method = "depthFunc(I)V", at = @At("HEAD"))
    private static void vulkanmod$depthFunc(int func, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.glDepthFun = func;
            VRenderSystem.depthFunc(func);
        }
    }

    @Inject(method = "enableCull", at = @At("HEAD"))
    private static void vulkanmod$enableCull(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.glCull = true;
            VRenderSystem.enableCull();
        }
    }

    @Inject(method = "disableCull", at = @At("HEAD"))
    private static void vulkanmod$disableCull(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.glCull = false;
            VRenderSystem.disableCull();
        }
    }
}
