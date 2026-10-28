package net.vulkanmod.mixin.render;

import net.minecraft.client.settings.GameSettings;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.gui.VulkanVideoOptions;
import net.vulkanmod.vulkan.Vulkan;
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
        String label = VulkanVideoOptions.label(option);
        if (label != null) {
            cir.setReturnValue(label);
        }
    }

    @Inject(method = "setOptionValue(Lnet/minecraft/client/settings/GameSettings$Options;I)V",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmod$customOptionClick(GameSettings.Options option, int value, CallbackInfo ci) {
        if (VulkanVideoOptions.click(option)) {
            ci.cancel();
        }
    }
}
