package net.vesu.mixin.render;

import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.entity.item.EntityItem;
import net.vesu.render.OcclusionCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A far dropped stack draws fewer of its up to five copies (see {@link OcclusionCuller#itemCopies}). */
@Mixin(RenderEntityItem.class)
public class RenderEntityItemM {

    @Inject(method = "transformModelCount", at = @At("RETURN"), cancellable = true)
    private void vesu$farItemCopies(EntityItem item, double x, double y, double z, float partialTicks, IBakedModel model,
                                    CallbackInfoReturnable<Integer> cir) {
        int copies = cir.getReturnValueI();
        int capped = OcclusionCuller.itemCopies(copies, x * x + y * y + z * z);
        if (capped != copies) cir.setReturnValue(capped);
    }
}
