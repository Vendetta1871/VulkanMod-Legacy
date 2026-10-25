package net.vulkanmod.mixin.render;

import com.cleanroommc.client.sdl.Window;
import net.vulkanmod.Initializer;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes Cleanroom's window teardown safe under the Vulkan renderer.
 *
 * <p>{@code Window.close()} unsets and destroys the window's OpenGL context on its way to
 * destroying the window. Under VulkanMod that context is never used for rendering, and once the
 * Vulkan renderer is active {@code SDL_GL_MakeCurrent} on the window faults inside libSDL3 on
 * Wayland, crashing the game on exit (the GL path exits cleanly, so it is specific to this
 * combination). The context is left to the process exit instead: on Wayland Cleanroom already
 * skips {@code SDL_Quit}, so nothing later touches it. Only the window itself is destroyed, which
 * is what actually has to happen.
 */
@Mixin(value = Window.class, remap = false)
public class WindowCloseM {

    /** Skip unsetting the (unused) GL context; the call faults under Vulkan on Wayland. */
    @Redirect(method = "close",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_MakeCurrent(JJ)Z"))
    private boolean vulkanmod$skipMakeCurrent(long window, long context) {
        if (Initializer.isVulkanActive()) {
            return false;
        }
        return SDLVideo.SDL_GL_MakeCurrent(window, context);
    }

    /** Skip destroying the (unused) GL context for the same reason; it leaks until process exit. */
    @Redirect(method = "close",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_DestroyContext(J)Z"))
    private boolean vulkanmod$skipDestroyContext(long context) {
        if (Initializer.isVulkanActive()) {
            return false;
        }
        return SDLVideo.SDL_GL_DestroyContext(context);
    }
}
