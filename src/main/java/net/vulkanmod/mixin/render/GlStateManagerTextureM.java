package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.TextureBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H6 remainder: the GlStateManager-level texture lifecycle. {@code deleteTexture} is the
 * funnel of TextureUtil.deleteTexture and AbstractTexture.deleteGlTexture, and mods call
 * it directly; the three upload entry points are the funnel of every TextureUtil upload
 * and of mods that upload through GlStateManager. They target the active unit's binding.
 * Raw GL11 calls stay unhooked (see TextureBridge.onRawUpload).
 */
@Mixin(GlStateManager.class)
public class GlStateManagerTextureM {

    @Inject(method = "deleteTexture(I)V", at = @At("HEAD"))
    private static void vulkanmod$deleteTexture(int texture, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            TextureBridge.invalidateForUpload(texture);
        }
    }

    @Inject(method = {"glTexImage2D(IIIIIIIILjava/nio/IntBuffer;)V",
            "glTexSubImage2D(IIIIIIIILjava/nio/IntBuffer;)V",
            "glCopyTexSubImage2D(IIIIIIII)V"}, at = @At("HEAD"))
    private static void vulkanmod$upload(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            TextureBridge.onRawUpload();
        }
    }
}
