package net.vesu.mixin.render;

import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.GuiIngameForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The two HUD elements {@link net.vesu.render.HudCache} draws outside the cached image. */
@Mixin(value = GuiIngameForge.class, remap = false)
public interface GuiIngameForgeInvoker {

    @Invoker("renderVignette")
    void vesu$renderVignette(float brightness, ScaledResolution res);

    @Invoker("renderCrosshairs")
    void vesu$renderCrosshairs(float partialTicks);
}
