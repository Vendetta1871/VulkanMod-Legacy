package net.vulkanmod.mixin.render;

import net.minecraft.client.settings.GameSettings;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.gui.VulkanVideoOptions;
import net.vulkanmod.vulkan.FrameTargets;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.rt.RtScene;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bridges vanilla {@code GameSettings} to the VulkanMod video settings entries.
 *
 * <p>Wires the in-game VSync option to the Vulkan swapchain (M8). Vanilla applies the
 * toggle through {@code Display.setVSyncEnabled}, which the swapchain ignores, so
 * {@code Vulkan.setVsync} had no caller and the option did nothing.</p>
 *
 * <p>{@code gameSettings.enableVsync} is the single source of truth: it is applied
 * once in {@code Vulkan.initVulkan} and here on every toggle, and vanilla persists
 * it in options.txt. The renderer config no longer carries its own vsync flag.</p>
 *
 * <p>The second part answers the option callbacks for the two custom
 * {@link GameSettings.Options} created by {@link VulkanVideoOptions}: label,
 * and slider values. Every other option falls through to vanilla.</p>
 */
@Mixin(GameSettings.class)
public abstract class GameSettingsM {

    @Inject(method = "setOptionValue(Lnet/minecraft/client/settings/GameSettings$Options;I)V",
            at = @At("RETURN"))
    private void vulkanmod$vsyncToggled(GameSettings.Options option, int value, CallbackInfo ci) {
        if (option == GameSettings.Options.ENABLE_VSYNC && Initializer.isVulkanActive()) {
            Vulkan.setVsync(((GameSettings) (Object) this).enableVsync);
        }
    }

    @Inject(method = "getKeyBinding(Lnet/minecraft/client/settings/GameSettings$Options;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmod$customOptionLabel(GameSettings.Options option, CallbackInfoReturnable<String> cir) {
        if (VulkanVideoOptions.isRtx(option)) {
            cir.setReturnValue(VulkanVideoOptions.rtxLabel());
        } else if (VulkanVideoOptions.isFsrResolution(option)) {
            cir.setReturnValue(VulkanVideoOptions.fsrResolutionLabel());
        }
    }

    @Inject(method = "getOptionFloatValue(Lnet/minecraft/client/settings/GameSettings$Options;)F",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmod$customOptionFloat(GameSettings.Options option, CallbackInfoReturnable<Float> cir) {
        if (VulkanVideoOptions.isFsrResolution(option)) {
            cir.setReturnValue(VulkanVideoOptions.fsrResolutionValue());
        } else if (VulkanVideoOptions.isRtx(option)) {
            cir.setReturnValue((float) Initializer.CONFIG.rtDistance);
        }
    }

    @Inject(method = "setOptionFloatValue(Lnet/minecraft/client/settings/GameSettings$Options;F)V",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmod$setCustomOptionFloat(GameSettings.Options option, float value, CallbackInfo ci) {
        if (VulkanVideoOptions.isRtx(option)) {
            VulkanVideoOptions.setRtDistance(value);
            ci.cancel();
            return;
        }
        if (!VulkanVideoOptions.isFsrResolution(option)) {
            return;
        }
        Initializer.CONFIG.renderScale = Math.max(VulkanVideoOptions.FSR_MIN,
                Math.min(VulkanVideoOptions.FSR_MAX, value));
        FrameTargets.applySettings();
        Initializer.saveConfig();
        ci.cancel();
    }
}
