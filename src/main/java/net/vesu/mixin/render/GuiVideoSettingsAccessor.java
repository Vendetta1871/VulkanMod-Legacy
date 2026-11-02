package net.vesu.mixin.render;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen the vanilla video settings return to, for VesuOptionsScreen (see MinecraftM). */
@Mixin(GuiVideoSettings.class)
public interface GuiVideoSettingsAccessor {
    @Accessor("parentGuiScreen")
    GuiScreen vesu$getParent();
}
