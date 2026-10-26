package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Raw access to the GL texture id field. @Shadow fields never reach this toolchain's
 * refmap (only accessor entries do), so in the SRG runtime a shadowed field would be
 * looked up under its dev name and fail to bind; the accessor is remapped instead.
 * Declared on AbstractTexture so the subclasses (DynamicTexture, ThreadDownloadImageData)
 * inherit the generated accessor as well.
 */
@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {

    @Accessor("glTextureId")
    int vulkanmod$getGlTextureId();
}
