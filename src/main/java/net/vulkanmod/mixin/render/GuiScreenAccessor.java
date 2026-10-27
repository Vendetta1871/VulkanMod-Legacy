package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Access to the screen's button list. @Shadow fields never reach this toolchain's
 * refmap (see {@link AbstractTextureAccessor}), so the inherited field is exposed
 * through a remapped accessor instead.
 */
@Mixin(GuiScreen.class)
public interface GuiScreenAccessor {

    @Accessor("buttonList")
    List<GuiButton> vulkanmod$getButtonList();
}
