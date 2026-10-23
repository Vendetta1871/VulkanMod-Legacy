package net.vulkanmod.mixin.render;

import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the Vulkan frame at the start of the world render; it is closed and
 * presented in {@code Minecraft.updateDisplay} (see MinecraftM). The return of
 * {@code updateCameraAndRender} is where the HUD and the current screen start
 * drawing, so the same hook closes the entity pass and opens the GUI pass.
 */
@Mixin(net.minecraft.client.renderer.EntityRenderer.class)
public class EntityRendererM {

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void vulkanmod$beginFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        GuiRenderer.setGuiPass(false);
        Initializer.beginFrame();
    }

    @Inject(method = "updateCameraAndRender", at = @At("RETURN"))
    private void vulkanmod$beginGui(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setGuiPass(true);
        }
    }
}
