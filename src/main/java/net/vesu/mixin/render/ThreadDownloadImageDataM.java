package net.vesu.mixin.render;

import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.vesu.Initializer;
import net.vesu.render.TextureBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.image.BufferedImage;

/**
 * When a downloaded skin arrives, {@code setBufferedImage} clears the uploaded flag and
 * the next {@code checkTextureUploaded} replaces the pixels under the texture's GL id
 * (without deleting it when the texture has no fallback location). The id-keyed Vulkan
 * mirror caches must be dropped there, otherwise multiplayer players keep the skin they
 * were first drawn with.
 */
@Mixin(ThreadDownloadImageData.class)
public abstract class ThreadDownloadImageDataM {

    // Accessors instead of @Shadow: shadowed fields never reach the refmap and would
    // not bind under the SRG runtime names.
    @Accessor("bufferedImage")
    abstract BufferedImage vesu$getBufferedImage();

    @Accessor("textureUploaded")
    abstract boolean vesu$isTextureUploaded();

    @Inject(method = "checkTextureUploaded", at = @At("HEAD"))
    private void vesu$aboutToReplaceTexture(CallbackInfo ci) {
        // Mirror the upload condition: only drop the cache when pixels are about to change.
        if (Initializer.isVulkanActive()
                && !vesu$isTextureUploaded()
                && vesu$getBufferedImage() != null) {
            int glTextureId = ((AbstractTextureAccessor) (Object) this).vesu$getGlTextureId();
            TextureBridge.invalidateGlTexture(glTextureId);
        }
    }
}
