package net.vesu.mixin.render;

import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import net.vesu.shader.PackIds;
import net.vesu.shader.PackManager;
import net.vesu.vulkan.Drawer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code currentRenderedItemId} of a pack's programs. */
@Mixin(RenderItem.class)
public class RenderItemM {

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V", at = @At("HEAD"))
    private void vesu$itemBegin(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        if (PackManager.active() != null) Drawer.setRenderedItem(PackIds.renderedItemId(stack.getItem()));
    }

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V", at = @At("RETURN"))
    private void vesu$itemEnd(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        if (PackManager.active() != null) Drawer.setRenderedItem(0);
    }
}
