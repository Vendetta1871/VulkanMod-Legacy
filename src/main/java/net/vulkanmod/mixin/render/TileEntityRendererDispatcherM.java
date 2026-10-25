package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Supplies the lightmap at each tile entity's own position to the captured TESR draws.
 * The block-entity quads (chest, sign, banner, skull, enchanting book, item frame - all
 * re-emitted as ITEM format through the shared BufferBuilder) carry no lightmap, and the
 * stale {@code currentEntity} would otherwise light them like the last mob rendered.
 *
 * <p>The capture window itself ({@code EntityRenderer.setEntityPass}) is opened by
 * {@code RenderGlobalM} around {@code renderEntities}: the Cleanroom dispatcher methods
 * are added code without SRG mappings and cannot be mixin targets.</p>
 */
@Mixin(TileEntityRendererDispatcher.class)
public class TileEntityRendererDispatcherM {

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V", at = @At("HEAD"))
    private void vulkanmod$beginTile(TileEntity te, double x, double y, double z,
                                     float partialTicks, int destroyStage, float alpha,
                                     CallbackInfo ci) {
        if (Initializer.isVulkanActive() && te.getWorld() != null) {
            EntityRenderer.setTesrLight(te.getWorld().getCombinedLight(te.getPos(), 0));
        }
    }
}
