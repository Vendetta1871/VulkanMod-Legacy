package net.vulkanmod.mixin.render;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the Vulkan frame at the start of the world render; it is closed and
 * presented in {@code Minecraft.updateDisplay} (see MinecraftM).
 *
 * <p>The GUI pass opens at the {@code setupOverlayRendering} calls inside
 * {@code updateCameraAndRender}: vanilla calls it right before the HUD
 * ({@code renderGameOverlay}) in the in-game branch and right before the current
 * screen ({@code ForgeHooksClient.drawScreen}) in the no-world branch, so both overlay
 * paths flip the flag while the Vulkan render pass is still recording (it ends in
 * {@code presentFrame}). The redirect is scoped to this method on purpose:
 * {@code renderWorldPass} also calls {@code setupOverlayRendering} for the fire
 * overlay, and opening the pass there would mis-capture the following world layers as
 * GUI. The next frame's HEAD closes the pass again.</p>
 */
@Mixin(net.minecraft.client.renderer.EntityRenderer.class)
public class EntityRendererM {

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void vulkanmod$beginFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        GuiRenderer.recordFrameBegin();
        GuiRenderer.setGuiPass(false);
        Initializer.beginFrame();
    }

    @Redirect(method = "updateCameraAndRender",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;setupOverlayRendering()V"))
    private void vulkanmod$beginGui(net.minecraft.client.renderer.EntityRenderer self) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.setGuiPass(true);
        }
        self.setupOverlayRendering();
    }
}
