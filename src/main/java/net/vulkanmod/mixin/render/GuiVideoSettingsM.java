package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.gui.UpscalingSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "Upscaling..." button to the vanilla video settings screen and opens
 * {@link UpscalingSettingsScreen} from it. Only shown while the Vulkan renderer
 * is the active one, since the settings do nothing on the GL path.
 */
@Mixin(GuiVideoSettings.class)
public class GuiVideoSettingsM {

    private static final int UPSCALING_BUTTON_ID = 7300;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void vulkanmod$addUpscalingButton(CallbackInfo ci) {
        if (!Initializer.isVulkanActive()) {
            return;
        }
        GuiScreen screen = (GuiScreen) (Object) this;
        // Vanilla Done is 200 wide at width/2-100, height-27; the new button sits
        // in the free space left of it on the same row.
        ((GuiScreenAccessor) this).vulkanmod$getButtonList().add(new GuiButton(UPSCALING_BUTTON_ID,
                screen.width / 2 - 204, screen.height - 27, 100, 20, "Upscaling..."));
    }

    @Inject(method = "actionPerformed(Lnet/minecraft/client/gui/GuiButton;)V",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmod$openUpscaling(GuiButton button, CallbackInfo ci) {
        if (button.id != UPSCALING_BUTTON_ID) {
            return;
        }
        GuiScreen screen = (GuiScreen) (Object) this;
        screen.mc.displayGuiScreen(new UpscalingSettingsScreen(screen));
        ci.cancel();
    }
}
