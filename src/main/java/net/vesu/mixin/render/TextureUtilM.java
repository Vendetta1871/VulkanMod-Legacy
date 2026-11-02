package net.vesu.mixin.render;

import java.awt.image.BufferedImage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.texture.TextureUtil;
import net.vesu.Initializer;
import net.vesu.render.TextureBridge;

/**
 * The AbstractTexture/DynamicTexture/ThreadDownloadImageData hooks cover the vanilla
 * texture lifecycle, but mods (and a few vanilla helpers) upload or delete through
 * these TextureUtil entry points directly, which leaves the Vulkan mirrors cached
 * against stale pixels or a dead GL id. Drop the mirror before the call so the next
 * draw re-downloads the current content (H6 remainder).
 *
 * <p>The block atlas id is excluded: its mirror is maintained tile-by-tile by
 * TextureBridge (see TextureAtlasSpriteM), and invalidating it on every sprite frame
 * upload would re-download the whole atlas each tick.</p>
 */
@Mixin(TextureUtil.class)
public class TextureUtilM {

    @Inject(method = "uploadTexture", at = @At("HEAD"))
    private static void vesu$uploadTexture(int textureId, int[] frameData, int width, int height,
                                                CallbackInfo ci) {
        invalidate(textureId);
    }

    /**
     * Uploads into the texture bound on the active unit. Inside TextureMap.updateAnimations
     * that is the animating atlas, whose tile is mirrored instead (R21).
     */
    @Inject(method = "uploadTextureMipmap", at = @At("HEAD"))
    private static void vesu$uploadTextureMipmap(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            TextureBridge.onSpriteUpload();
        }
    }

    @Inject(method = "uploadTextureImage", at = @At("HEAD"))
    private static void vesu$uploadTextureImage(int textureId, BufferedImage image,
                                                     CallbackInfoReturnable<Integer> cir) {
        invalidate(textureId);
    }

    @Inject(method = "uploadTextureImageAllocate", at = @At("HEAD"))
    private static void vesu$uploadTextureImageAllocate(int textureId, BufferedImage image,
                                                             boolean blur, boolean clamp,
                                                             CallbackInfoReturnable<Integer> cir) {
        invalidate(textureId);
    }

    @Inject(method = "uploadTextureImageSub", at = @At("HEAD"))
    private static void vesu$uploadTextureImageSub(int textureId, BufferedImage image, int x, int y,
                                                        boolean blur, boolean clamp,
                                                        CallbackInfoReturnable<Integer> cir) {
        invalidate(textureId);
    }

    private static void invalidate(int texId) {
        TextureBridge.invalidateForUpload(texId);
    }
}
