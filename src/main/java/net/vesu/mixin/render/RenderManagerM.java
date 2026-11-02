package net.vesu.mixin.render;

import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.vesu.Initializer;
import net.vesu.render.EntityRenderer;
import net.vesu.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets {@code RenderManager.renderEntity} so {@code WorldVertexBufferUploaderM}
 * knows the geometry it is about to draw belongs to an entity. The flag is cleared on
 * return; a throw mid-render leaves it set, but the next {@code renderEntity} head and
 * the frame start both reset it, and only model-format draws are captured regardless.
 * During a GUI pass ({@link GuiRenderer#isGuiPass()}) the window stays closed: the
 * inventory doll renders through this same method but belongs to the GUI capture.
 */
@Mixin(RenderManager.class)
public class RenderManagerM {

    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V", at = @At("HEAD"))
    private void vesu$beginEntity(Entity entity, double x, double y, double z,
                                       float yaw, float partialTicks, boolean renderOutlines,
                                       CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            net.vesu.debug.FrameProfiler.entity();
            // The inventory doll (GuiContainer.drawEntityOnScreen) reaches renderEntity
            // during the GUI pass. There GuiRenderer owns the draws and replays the model
            // under the GUI ortho, so the entity capture window must stay closed; the
            // entity is still recorded so packedLight() lights the doll correctly.
            if (!GuiRenderer.isGuiPass()) {
                EntityRenderer.setEntityPass(true);
            }
            EntityRenderer.setCurrentEntity(entity);
        }
    }

    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V", at = @At("RETURN"))
    private void vesu$endEntity(Entity entity, double x, double y, double z,
                                     float yaw, float partialTicks, boolean renderOutlines,
                                     CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            EntityRenderer.setEntityPass(false);
        }
    }
}
