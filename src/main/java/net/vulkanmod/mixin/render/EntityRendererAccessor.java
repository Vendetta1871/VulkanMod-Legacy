package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the private lightmap texture. Reflection by the dev (MCP) field name
 * breaks in the SRG runtime, where the field is called {@code field_78513_d}, and a
 * failed lookup used to be retried (throwing again) on every call; the accessor is
 * remapped through the refmap instead.
 */
@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {

    @Accessor("lightmapTexture")
    DynamicTexture vulkanmod$getLightmapTexture();
}
