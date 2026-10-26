package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.vulkanmod.render.TextureBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mirrors animated sprite frames (water, lava, fire, ...) into the Vulkan atlas as they are
 * uploaded to GL, instead of flagging the whole atlas for a {@code glGetTexImage} re-download.
 *
 * <p>Both animation upload paths ({@code updateAnimation} for a plain frame advance and
 * {@code updateAnimationInterpolated} for the CPU-blended intermediate frames of interpolated
 * animations like water) go through {@code TextureUtil.uploadTextureMipmap} with the final
 * pixel array, so redirecting those two call sites captures exactly the pixels vanilla writes
 * - including the blend, which never appears in {@code framesTextureData}.</p>
 */
@Mixin(TextureAtlasSprite.class)
public class TextureAtlasSpriteM {

    @Redirect(method = "updateAnimation",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureUtil;uploadTextureMipmap([[IIIIIZZ)V"))
    private void vulkanmod$mirrorFrameAdvance(int[][] frames, int width, int height, int x, int y,
                                              boolean blur, boolean clamp) {
        TextureUtil.uploadTextureMipmap(frames, width, height, x, y, blur, clamp);
        TextureBridge.uploadSpriteTile(frames, width, height, x, y);
    }

    @Redirect(method = "updateAnimationInterpolated",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureUtil;uploadTextureMipmap([[IIIIIZZ)V"))
    private void vulkanmod$mirrorInterpolatedFrame(int[][] frames, int width, int height, int x, int y,
                                                   boolean blur, boolean clamp) {
        TextureUtil.uploadTextureMipmap(frames, width, height, x, y, blur, clamp);
        TextureBridge.uploadSpriteTile(frames, width, height, x, y);
    }
}
