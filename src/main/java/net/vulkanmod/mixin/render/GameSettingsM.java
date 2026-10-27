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
 *
 * <p>{@code gameSettings.enableVsync} is the single source of truth: it is applied
 * once in {@code Vulkan.initVulkan} and here on every toggle, and vanilla persists
 * it in options.txt. The renderer config no longer carries its own vsync flag.</p>
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
