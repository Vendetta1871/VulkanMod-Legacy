package net.vesu.mixin.render;

import net.minecraftforge.client.model.pipeline.VertexLighterSmoothAo;
import net.vesu.render.ChunkBatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code separateAo=true}: AO goes to the vertex alpha, the pack applies it where it wants. */
@Mixin(value = VertexLighterSmoothAo.class, remap = false)
public abstract class VertexLighterSmoothAoM {

    @Shadow
    protected abstract float getAo(float x, float y, float z);

    @Unique
    private float vesu$ao;

    @Redirect(method = "updateColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/client/model/pipeline/VertexLighterSmoothAo;getAo(FFF)F"))
    private float vesu$ao(VertexLighterSmoothAo self, float x, float y, float z) {
        float ao = getAo(x, y, z);
        if (!ChunkBatcher.separateAo) return ao;
        vesu$ao = ao;
        return 1.0f;
    }

    @Inject(method = "updateColor", at = @At("RETURN"))
    private void vesu$aoToAlpha(float[] normal, float[] color, float x, float y, float z, float tint,
                                     int multiplier, CallbackInfo ci) {
        if (ChunkBatcher.separateAo) color[3] = vesu$ao;
    }
}
