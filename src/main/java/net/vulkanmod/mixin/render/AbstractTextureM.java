package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.EntityRenderer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the captured-texture caches when a GL texture is deleted. Every vanilla texture
 * (GUI sheets, fonts, skins, the loading stages) is an {@link AbstractTexture} and is
 * freed through {@code deleteGlTexture}; GL then hands the same texture id to the next
 * generated texture. Without this hook the id-keyed Vulkan caches keep serving the dead
 * texture's image under the new texture's id - the hotbar slots rendering with a stale
 * loading-screen sheet being the visible symptom.
 */
@Mixin(AbstractTexture.class)
public class AbstractTextureM {

    @Shadow
    protected int glTextureId;

    @Inject(method = "deleteGlTexture", at = @At("HEAD"))
    private void vulkanmod$textureDeleted(CallbackInfo ci) {
        if (Initializer.isVulkanActive() && glTextureId > 0) {
            GuiRenderer.onTextureDeleted(glTextureId);
            EntityRenderer.onTextureDeleted(glTextureId);
        }
    }
}
