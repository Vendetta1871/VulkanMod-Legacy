package net.vesu.mixin.render;

import java.awt.image.BufferedImage;
import java.io.IOException;
import net.minecraft.client.renderer.texture.SimpleTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * An unreadable image makes {@code TextureUtil.readBufferedImage} return null (ImageIO
 * finds no reader, e.g. the empty stream a resource pack zip's directory entry yields for
 * {@code TextureManager.RESOURCE_LOCATION_EMPTY}), and {@code loadTexture} would hand that
 * null to the upload and crash with a NullPointerException. Throwing the IOException
 * vanilla expects lets {@code TextureManager.loadTexture} fall back to the missing texture.
 */
@Mixin(SimpleTexture.class)
public class SimpleTextureM {

    @ModifyArg(method = "loadTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureUtil;uploadTextureImageAllocate(ILjava/awt/image/BufferedImage;ZZ)I"),
            index = 1)
    private BufferedImage vesu$requireImage(BufferedImage image) throws IOException {
        if (image == null) {
            throw new IOException("Unreadable image");
        }
        return image;
    }
}
