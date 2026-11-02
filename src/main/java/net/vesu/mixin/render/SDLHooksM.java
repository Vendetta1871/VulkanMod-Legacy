package net.vesu.mixin.render;

import com.cleanroommc.client.sdl.SDLHooks;
import net.vesu.config.DisplayModes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After Cleanroom enters fullscreen (toggle or start-up), switch to the chosen video mode. */
@Mixin(value = SDLHooks.class, remap = false)
public class SDLHooksM {

    @Inject(method = "applyDisplayMode", at = @At("RETURN"))
    private static void vesu$fullscreenMode(boolean fullscreen, CallbackInfo ci) {
        DisplayModes.apply(fullscreen, false);
    }
}
