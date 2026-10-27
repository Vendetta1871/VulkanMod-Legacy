package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.FontRenderer;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brackets every string draw (R7, M11/N18). {@code renderString} is the single private
 * funnel of drawString (shadow and main pass), drawStringWithShadow and
 * drawSplitString/renderStringAligned. Inside the bracket no matrix op runs (italics and
 * bold are vertex offsets), so the glyph run may trust the matrix version; outside it the
 * run is closed, so raw GL11 matrix calls between strings cannot leak into a batch.
 */
@Mixin(FontRenderer.class)
public class FontRendererM {

    @Inject(method = "renderString(Ljava/lang/String;FFIZ)I", at = @At("HEAD"))
    private void vulkanmod$beginString(CallbackInfoReturnable<Integer> cir) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.beginFontString();
        }
    }

    @Inject(method = "renderString(Ljava/lang/String;FFIZ)I", at = @At("RETURN"))
    private void vulkanmod$endString(CallbackInfoReturnable<Integer> cir) {
        if (Initializer.isVulkanActive()) {
            GuiRenderer.endFontString();
        }
    }
}
