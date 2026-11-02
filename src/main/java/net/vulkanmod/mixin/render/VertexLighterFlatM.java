package net.vulkanmod.mixin.render;

import net.minecraftforge.client.model.pipeline.LightUtil;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import net.vulkanmod.render.ChunkBatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code oldLighting=false}: the pack shades faces itself. */
@Mixin(value = VertexLighterFlat.class, remap = false)
public class VertexLighterFlatM {

    @Redirect(method = "processQuad", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/client/model/pipeline/LightUtil;diffuseLight(FFF)F"))
    private float vulkanmod$diffuse(float x, float y, float z) {
        return ChunkBatcher.plainShade ? 1.0f : LightUtil.diffuseLight(x, y, z);
    }
}
