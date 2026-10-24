package net.vulkanmod.mixin.render;

import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.debug.FrameProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the Vulkan frame loop: initializes the renderer once the window exists,
 * presents frames in place of the GL swap and marks the swapchain for recreation
 * on window resize.
 */
@Mixin(Minecraft.class)
public class MinecraftM {

    @Inject(method = "init", at = @At("TAIL"))
    private void vulkanmod$initVulkan(CallbackInfo ci) {
        Initializer.initVulkan();
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void vulkanmod$frameBegin(CallbackInfo ci) {
        FrameProfiler.frameBegin();
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$presentFrame(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            FrameProfiler.presentBegin();
            Initializer.presentFrame();
            FrameProfiler.presentEnd();
            ci.cancel();
        } else {
            // Keep retrying until the window exists; vanilla GL presents meanwhile.
            Initializer.initVulkan();
        }
    }

    @Inject(method = "updateFramebufferSize", at = @At("HEAD"))
    private void vulkanmod$onResize(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        Initializer.onFramebufferSize(mc.displayWidth, mc.displayHeight);
    }
}
