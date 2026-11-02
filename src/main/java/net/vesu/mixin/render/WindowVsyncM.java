package net.vesu.mixin.render;

import com.cleanroommc.client.sdl.Window;
import net.vesu.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Under the Vulkan renderer the swapchain's present mode is the vsync (see GameSettingsM),
 * and the window's unused GL context must not be touched: {@code SDL_GL_SetSwapInterval},
 * which {@code Minecraft.toggleFullscreen} reaches through {@code Display.setVSyncEnabled},
 * faults inside libSDL3, so switching to or from fullscreen crashed the game.
 */
@Mixin(value = Window.class, remap = false)
public class WindowVsyncM {

    @Inject(method = "vsync", at = @At("HEAD"), cancellable = true)
    private void vesu$noGlSwapInterval(boolean enabled, CallbackInfoReturnable<Window> cir) {
        if (Initializer.isVulkanActive()) cir.setReturnValue((Window) (Object) this);
    }
}
