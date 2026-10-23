package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.GuiScreen;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the current screen render (the main menu, inventories, settings, ...) the
 * same way {@link GuiIngameM} brackets the HUD. Nested {@code super.drawScreen} calls
 * just re-set the flag, so the flag stays true until the outermost screen returns.
 */
@Mixin(GuiScreen.class)
public class GuiScreenM {

    @Inject(method = "drawScreen(IIF)V", at = @At("HEAD"))
    private void vulkanmod$beginScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setGuiPass(true);
        }
    }

    @Inject(method = "drawScreen(IIF)V", at = @At("RETURN"))
    private void vulkanmod$endScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setGuiPass(false);
        }
    }
}
