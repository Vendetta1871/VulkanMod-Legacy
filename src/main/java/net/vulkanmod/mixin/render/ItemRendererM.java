package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.ItemRenderer;
import net.vulkanmod.shader.PackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code underwaterOverlay=false} in shaders.properties. */
@Mixin(ItemRenderer.class)
public class ItemRendererM {

    @Inject(method = "renderWaterOverlayTexture", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$waterOverlay(float partialTicks, CallbackInfo ci) {
        if (PackManager.rendererIsFalse("underwaterOverlay")) ci.cancel();
    }
}
