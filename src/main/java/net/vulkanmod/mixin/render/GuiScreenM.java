package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.GuiScreen;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the GUI pass around the current screen render (the main menu, inventories,
 * settings, ...) as a safety net for drawScreen calls outside {@code updateCameraAndRender}.
 * The flag is deliberately never closed here: {@code GuiContainer.drawScreen} calls
 * {@code super.drawScreen} in the middle of its own body and then renders the slot items,
 * so closing on return would drop every {@code drawSlot} draw. The frame head resets the
 * flag instead.
 */
@Mixin(GuiScreen.class)
public class GuiScreenM {

    @Inject(method = "drawScreen(IIF)V", at = @At("HEAD"))
    private void vulkanmod$beginScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setGuiPass(true);
        }
    }
}
