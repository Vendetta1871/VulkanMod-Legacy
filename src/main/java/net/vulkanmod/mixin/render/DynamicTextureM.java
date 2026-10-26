package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.TextureBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A {@link DynamicTexture} (map, server icon, the lightmap) re-uploads new pixels under
 * the same GL id, so the id-keyed Vulkan mirror caches must be dropped when its content
 * changes; otherwise a map would show the frame it was first captured in.
 */
@Mixin(DynamicTexture.class)
public class DynamicTextureM {

    @Inject(method = "updateDynamicTexture", at = @At("HEAD"))
    private void vulkanmod$contentChanged(CallbackInfo ci) {
        int glTextureId = ((AbstractTextureAccessor) (Object) this).vulkanmod$getGlTextureId();
        if (Initializer.isVulkanActive() && glTextureId > 0) {
            TextureBridge.invalidateGlTexture(glTextureId);
        }
    }
}
