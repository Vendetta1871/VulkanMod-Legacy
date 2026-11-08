package net.vesu.mixin.render;

import net.minecraftforge.client.GuiIngameForge;
import net.vesu.render.HudCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The crosshair blends with the world under it, so it stays out of the cached HUD image;
 * {@link HudCache} draws it every frame instead. A Forge class: names are not remapped.
 * The vignette is a vanilla method GuiIngameForge only inherits, see {@link GuiIngameM}.
 */
@Mixin(value = GuiIngameForge.class, remap = false)
public class GuiIngameForgeM {

    @Inject(method = "renderCrosshairs", at = @At("HEAD"), cancellable = true)
    private void vesu$uncachedCrosshairs(float partialTicks, CallbackInfo ci) {
        if (HudCache.capturing()) ci.cancel();
    }
}
