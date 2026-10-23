package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.Gui;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEMP diagnostics: check whether vanilla Gui helpers run during the GUI pass. */
@Mixin(Gui.class)
public class GuiDiagM {

    @Inject(method = "drawTexturedModalRect(IIIIII)V", at = @At("HEAD"))
    private void vulkanmod$diagTex(int x, int y, int u, int v, int width, int height, CallbackInfo ci) {
        if (Initializer.isVulkanActive() && GuiRenderer.isGuiPass() && GuiRenderer.diagGuiHelper()) {
            Initializer.LOGGER.info("[gui] Gui.drawTexturedModalRect called");
        }
    }

    @Inject(method = "drawGradientRect(IIIIII)V", at = @At("HEAD"))
    private void vulkanmod$diagGrad(int left, int top, int right, int bottom,
                                    int startColor, int endColor, CallbackInfo ci) {
        if (Initializer.isVulkanActive() && GuiRenderer.isGuiPass() && GuiRenderer.diagGuiHelper()) {
            Initializer.LOGGER.info("[gui] Gui.drawGradientRect called");
        }
    }
}
