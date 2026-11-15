package net.vesu.mixin.render;

import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.event.ClickEvent;
import net.vesu.Initializer;
import net.vesu.vulkan.Drawer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * F2 under the Vulkan renderer: vanilla reads the pixels back from the GL framebuffer, which
 * Vulkan never draws into (a blank or failed screenshot). The presented swapchain image is
 * captured instead (Drawer.requestCapture), written when the frame completes, under vanilla's
 * file name and with its chat message.
 */
@Mixin(ScreenShotHelper.class)
public abstract class ScreenShotHelperM {

    @Inject(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/shader/Framebuffer;)Lnet/minecraft/util/text/ITextComponent;",
            at = @At("HEAD"), cancellable = true)
    private static void vesu$vulkanScreenshot(File gameDirectory, String name, int width, int height,
                                              Framebuffer buffer, CallbackInfoReturnable<ITextComponent> cir) {
        if (!Initializer.isVulkanActive()) return;
        File dir = new File(gameDirectory, "screenshots");
        dir.mkdirs();
        File file;
        if (name == null) {
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
            int i = 1;
            do {
                file = new File(dir, stamp + (i == 1 ? "" : "_" + i) + ".png");
                i++;
            } while (file.exists());
        } else {
            file = new File(dir, name);
        }
        Drawer.requestCapture(file);
        ITextComponent link = new TextComponentString(file.getName());
        link.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath()));
        link.getStyle().setUnderlined(true);
        cir.setReturnValue(new TextComponentTranslation("screenshot.success", link));
    }
}
