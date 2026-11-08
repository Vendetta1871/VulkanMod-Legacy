package net.vesu.mixin.render;

import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The vignette {@link net.vesu.render.HudCache} draws outside the cached image. A vanilla method,
 * so it is remapped: GuiIngameForge only inherits it and a Forge-class invoker finds nothing in
 * production.
 */
@Mixin(GuiIngame.class)
public interface GuiIngameInvoker {

    @Invoker("renderVignette")
    void vesu$renderVignette(float lightLevel, ScaledResolution scaledRes);
}
