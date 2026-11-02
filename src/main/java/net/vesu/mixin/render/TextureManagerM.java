package net.vesu.mixin.render;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.vesu.shader.PbrAtlases;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells a model's material from the texture bound for it. */
@Mixin(TextureManager.class)
public class TextureManagerM {

    @Inject(method = "bindTexture", at = @At("RETURN"))
    private void vesu$noteBound(ResourceLocation resource, CallbackInfo ci) {
        PbrAtlases.noteBound(resource);
    }
}
