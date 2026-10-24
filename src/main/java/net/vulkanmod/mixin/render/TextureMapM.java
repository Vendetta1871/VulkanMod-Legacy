package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import net.vulkanmod.render.TextureBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the block atlas for a full re-download after it has been (re)stitched, e.g. on a
 * resource pack reload. Per-tick animation changes are mirrored tile-by-tile by
 * {@link TextureAtlasSpriteM}; the bracketing here gives those mirrors a shared transfer
 * command buffer so one tick submits at most once.
 */
@Mixin(TextureMap.class)
public class TextureMapM {

    @Inject(method = "loadTextureAtlas", at = @At("RETURN"))
    private void vulkanmod$atlasReloaded(IResourceManager resourceManager, CallbackInfo ci) {
        TextureBridge.markAtlasDirty();
    }

    // The per-tick animation uploads (TextureAtlasSpriteM -> TextureBridge.uploadSpriteTile)
    // all belong together: record them into one transfer command buffer and submit once.
    @Inject(method = "updateAnimations", at = @At("HEAD"))
    private void vulkanmod$beginTileBatch(CallbackInfo ci) {
        TextureBridge.beginTileBatch();
    }

    @Inject(method = "updateAnimations", at = @At("TAIL"))
    private void vulkanmod$endTileBatch(CallbackInfo ci) {
        TextureBridge.endTileBatch();
    }
}
