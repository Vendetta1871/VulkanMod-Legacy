package net.vesu.mixin.render;

import net.minecraftforge.client.GuiIngameForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The crosshair {@link net.vesu.render.HudCache} draws outside the cached image (the vignette: {@link GuiIngameInvoker}). */
@Mixin(value = GuiIngameForge.class, remap = false)
public interface GuiIngameForgeInvoker {

    @Invoker("renderCrosshairs")
    void vesu$renderCrosshairs(float partialTicks);
}
