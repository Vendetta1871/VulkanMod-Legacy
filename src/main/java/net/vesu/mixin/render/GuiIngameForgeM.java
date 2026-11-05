package net.vesu.mixin.render;

import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.GuiIngameForge;
import net.vesu.render.HudCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vignette and the crosshair blend with the world under them, so they stay out of the
 * cached HUD image; {@link HudCache} draws them every frame instead. A Forge class: names are
 * not remapped.
 */
@Mixin(value = GuiIngameForge.class, remap = false)
public class GuiIngameForgeM {

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void vesu$uncachedVignette(float brightness, ScaledResolution res, CallbackInfo ci) {
        if (HudCache.capturing()) ci.cancel();
    }

    @Inject(method = "renderCrosshairs", at = @At("HEAD"), cancellable = true)
    private void vesu$uncachedCrosshairs(float partialTicks, CallbackInfo ci) {
        if (HudCache.capturing()) ci.cancel();
    }
}
