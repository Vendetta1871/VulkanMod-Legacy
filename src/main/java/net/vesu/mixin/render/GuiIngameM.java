package net.vesu.mixin.render;

import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.vesu.render.HudCache;
import net.vesu.shader.PackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code vignette=false} in shaders.properties. The vignette also stays out of the cached HUD
 * image ({@link HudCache} draws it every frame instead).
 */
@Mixin(GuiIngame.class)
public class GuiIngameM {

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void vesu$vignette(float lightLevel, ScaledResolution resolution, CallbackInfo ci) {
        if (HudCache.capturing() || PackManager.rendererIsFalse("vignette")) ci.cancel();
    }
}
