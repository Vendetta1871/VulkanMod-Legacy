package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.BlockFluidRenderer;
import net.vulkanmod.render.ChunkBatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** {@code oldLighting=false} for liquids, whose face shade is written in the renderer. */
@Mixin(BlockFluidRenderer.class)
public class BlockFluidRendererM {

    @ModifyConstant(method = "renderFluid", constant = {
            @Constant(floatValue = 0.5f, ordinal = 0),
            @Constant(floatValue = 0.8f, ordinal = 0),
            @Constant(floatValue = 0.6f, ordinal = 0)})
    private float vulkanmod$faceShade(float shade) {
        return ChunkBatcher.plainShade ? 1.0f : shade;
    }
}
