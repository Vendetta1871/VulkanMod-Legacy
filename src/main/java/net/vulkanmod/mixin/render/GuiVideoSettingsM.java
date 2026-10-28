package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.settings.GameSettings;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.gui.VulkanVideoOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Appends VulkanMod's rows ("Upscaling...", "Ray Tracing...", see VulkanVideoOptions) to the
 * option array {@code initGui} passes to the {@code GuiOptionsRowList} constructor, only while
 * the Vulkan renderer is active. Both branches of that constructor call (with and without the
 * USE_VBO filtering) get the extended array, so the rows show on either path.
 */
@Mixin(GuiVideoSettings.class)
public class GuiVideoSettingsM {

    @ModifyArg(method = "initGui",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiOptionsRowList;<init>(Lnet/minecraft/client/Minecraft;IIIII[Lnet/minecraft/client/settings/GameSettings$Options;)V"),
            index = 6)
    private GameSettings.Options[] vulkanmod$appendVulkanOptions(GameSettings.Options[] options) {
        if (!Initializer.isVulkanActive()) {
            return options;
        }
        return VulkanVideoOptions.appendTo(options);
    }
}
