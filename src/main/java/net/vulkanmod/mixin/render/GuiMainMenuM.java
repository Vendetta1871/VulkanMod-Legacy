package net.vulkanmod.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.vulkanmod.Initializer;
import net.vulkanmod.VulkanModMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prints "VulkanMod &lt;version&gt;" in the main menu's bottom-left corner, directly above
 * the loader branding stack ("Cleanroom ... / N mods loaded"), so the renderer
 * replacement is announced on the title screen like any other loader-level mod.
 *
 * <p>Drawn just before the loader's branding loop (the {@code getBrandings} call), i.e.
 * after the panorama and logo have been painted but while the GUI capture pass is still
 * open (it is closed by {@code GuiScreenM} when {@code super.drawScreen} returns, which
 * {@code GuiMainMenu} calls further down) and with the {@code setupOverlayRendering} ortho
 * in place, so the text is replayed in Vulkan at screen coordinates and is not covered by
 * the panorama.</p>
 */
@Mixin(GuiMainMenu.class)
public class GuiMainMenuM {

    @Inject(method = "drawScreen(IIF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/fml/common/FMLCommonHandler;getBrandings(Z)Ljava/util/List;",
                    shift = At.Shift.BEFORE))
    private void vulkanmod$drawModVersion(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!Initializer.isVulkanActive()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        int screenHeight = new ScaledResolution(mc).getScaledHeight();

        // Sit one line above the topmost branding row; the loader draws its lines upward
        // from height - 10, one per (FONT_HEIGHT + 1) pixels.
        int lines = FMLCommonHandler.instance().getBrandings(true).size();
        int y = screenHeight - (10 + lines * (font.FONT_HEIGHT + 1));

        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        font.drawString(VulkanModMod.NAME + " " + VulkanModMod.VERSION, 2, y, 0xFFFFFF);
    }
}
