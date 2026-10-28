package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The block atlas's stitched sprites, for the RT material atlas (RtMaterials). */
@Mixin(TextureMap.class)
public interface TextureMapAccessor {

    @Accessor("mapUploadedSprites")
    Map<String, TextureAtlasSprite> vulkanmod$getUploadedSprites();
}
