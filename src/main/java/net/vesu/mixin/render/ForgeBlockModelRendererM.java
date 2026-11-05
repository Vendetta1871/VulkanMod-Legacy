package net.vesu.mixin.render;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.model.pipeline.ForgeBlockModelRenderer;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import net.vesu.render.FastBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunk-build blocks go through {@link FastBlockRenderer} when it can produce Forge's output. A Forge class: not remapped. */
@Mixin(value = ForgeBlockModelRenderer.class, remap = false)
public class ForgeBlockModelRendererM {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private static void vesu$fastRender(VertexLighterFlat lighter, IBlockAccess world, IBakedModel model, IBlockState state, BlockPos pos,
                                        BufferBuilder buffer, boolean checkSides, long rand, CallbackInfoReturnable<Boolean> cir) {
        if (FastBlockRenderer.verifying()) return;
        Boolean rendered = FastBlockRenderer.render(lighter, world, model, state, pos, buffer, checkSides, rand);
        if (rendered != null) cir.setReturnValue(rendered);
    }
}
