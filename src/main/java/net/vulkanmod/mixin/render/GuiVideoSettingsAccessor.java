package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiVideoSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The option list of the video settings screen (the autotest scrolls it). */
@Mixin(GuiVideoSettings.class)
public interface GuiVideoSettingsAccessor {
    @Accessor("optionsRowList")
    GuiListExtended vulkanmod$getOptionsRowList();
}
