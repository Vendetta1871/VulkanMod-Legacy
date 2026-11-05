package net.vesu.mixin.render;

import net.minecraft.block.BlockLeaves;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.vesu.render.LeafCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fancy leaf faces hidden behind other leaves are not built (see {@link LeafCulling}). */
@Mixin(BlockLeaves.class)
public class BlockLeavesM {

    @Inject(method = "shouldSideBeRendered", at = @At("HEAD"), cancellable = true)
    private void vesu$cullLeafSide(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (LeafCulling.cullSide(world, pos, side)) cir.setReturnValue(false);
    }
}
