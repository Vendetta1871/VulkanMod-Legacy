package net.vesu.mixin.render;

import net.vesu.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures the SDL window handle when Cleanroom's own SDL window stack creates
 * the game window, so the Vulkan renderer can build its surface on it.
 */
@Mixin(value = com.cleanroommc.client.sdl.Window.Builder.class, remap = false)
public class WindowBuilderM {

    @Inject(method = "build", at = @At("RETURN"))
    private void vesu$capture(CallbackInfoReturnable<?> cir) {
        Object window = cir.getReturnValue();
        try {
            long handle = (long) window.getClass().getMethod("handle").invoke(window);
            Initializer.onWindowCreated(handle);
        } catch (Throwable t) {
            Initializer.LOGGER.info("Window capture failed: {}", t.toString());
        }
    }
}
