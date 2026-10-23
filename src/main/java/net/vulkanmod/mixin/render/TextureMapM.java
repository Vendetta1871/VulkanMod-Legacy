package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import net.vulkanmod.render.TextureBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the block atlas for re-download after it has been (re)stitched, e.g. on a
 * resource pack reload. Per-frame animation changes are handled precisely by
 * {@link TextureAtlasSpriteM}, which only flags the atlas when a sprite actually
 * advances a frame (rather than on every {@code updateAnimations} tick).
 */
@Mixin(TextureMap.class)
public class TextureMapM {

    @Inject(method = "loadTextureAtlas", at = @At("RETURN"))
    private void vulkanmod$atlasReloaded(IResourceManager resourceManager, CallbackInfo ci) {
        TextureBridge.markAtlasDirty();
    }
}
