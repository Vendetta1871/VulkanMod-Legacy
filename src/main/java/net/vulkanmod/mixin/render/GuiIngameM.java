package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.GuiIngame;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the HUD render so {@code WorldVertexBufferUploaderM} knows the geometry it
 * is about to upload belongs to the GUI pass. The flag is cleared on return; a throw
 * mid-render leaves it set, but the next head and the frame start both reset it, and
 * only GUI-format draws are captured regardless.
 */
@Mixin(GuiIngame.class)
public class GuiIngameM {

    @Inject(method = "renderGameOverlay(F)V", at = @At("HEAD"))
    private void vulkanmod$beginGui(float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setGuiPass(true);
        }
    }

    @Inject(method = "renderGameOverlay(F)V", at = @At("RETURN"))
    private void vulkanmod$endGui(float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setGuiPass(false);
        }
    }
}
