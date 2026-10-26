package net.vulkanmod.mixin.render;

import net.minecraft.client.settings.GameSettings;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wires the in-game VSync option to the Vulkan swapchain (M8). Vanilla applies the
 * toggle through {@code Display.setVSyncEnabled}, which the swapchain ignores, so
 * {@code Vulkan.setVsync} had no caller and the option did nothing.
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
}
