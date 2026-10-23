package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.GuiScreen;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEMP diagnostics: check whether GuiScreen.drawDefaultBackground runs. */
@Mixin(GuiScreen.class)
public class GuiScreenDiagM {

    @Inject(method = "drawDefaultBackground", at = @At("HEAD"))
    private void vulkanmod$diagBg(CallbackInfo ci) {
        if (Initializer.isVulkanActive() && GuiRenderer.diagGuiHelper()) {
            Initializer.LOGGER.info("[gui] GuiScreen.drawDefaultBackground called (guiPass={})",
                    GuiRenderer.isGuiPass());
        }
    }
}
