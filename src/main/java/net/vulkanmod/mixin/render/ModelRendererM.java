package net.vulkanmod.mixin.render;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.vulkanmod.render.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Mob model parts are normally replayed from a GL display list ({@code glCallList}),
 * which never routes through the {@link BufferBuilder} the Vulkan capture reads. While
 * an entity is rendering this redirects the {@code callList} in {@code render} and
 * {@code renderWithRotation} to rebuild the boxes into the shared BufferBuilder instead,
 * so each quad flows through {@code WorldVertexBufferUploader.draw} and gets captured.
 * The per-part translate/rotate the original method applies before the call is left in
 * place, so the live GL modelview still carries the full part transform.
 *
 * <p>The display-list compile itself is flagged so its (recording-only) draws are not
 * captured with the wrong matrix.</p>
 *
 * <p>The same rebuild runs for the hand pass: the first-person arms are drawn through
 * {@code ModelRenderer.render} inside {@code renderHand}.</p>
 */
@Mixin(ModelRenderer.class)
public class ModelRendererM {

    @Shadow
    public List<ModelBox> cubeList;

    private float vulkanmod$scale;

    @Inject(method = {"render(F)V", "renderWithRotation(F)V"}, at = @At("HEAD"))
    private void vulkanmod$storeScale(float scale, CallbackInfo ci) {
        this.vulkanmod$scale = scale;
    }

    @Redirect(method = {"render(F)V", "renderWithRotation(F)V"},
            at = @At(value = "INVOKE", target = "net/minecraft/client/renderer/GlStateManager.callList(I)V"))
    private void vulkanmod$rebuildOrCallList(int list) {
        if (EntityRenderer.isEntityPass() || EntityRenderer.isHandPass()) {
            net.vulkanmod.debug.FrameProfiler.entityRebuildBegin();
            // Accumulate the part's quads (each box.render flushes one quad) and issue them
            // as a single draw - they all share this part's model-view, texture and tint.
            boolean batch = EntityRenderer.BATCH_PARTS;
            if (batch) {
                EntityRenderer.beginPart();
            }
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            for (ModelBox box : this.cubeList) {
                box.render(buffer, this.vulkanmod$scale);
            }
            if (batch) {
                EntityRenderer.endPart();
            }
            net.vulkanmod.debug.FrameProfiler.entityRebuildEnd();
        } else if (net.vulkanmod.render.GuiRenderer.isGuiPass()) {
            // Builtin item icons (the chest, the shulker box, ...) render their baked
            // boxes through the TESR dispatcher during the GUI pass; rebuild them the
            // same way so the batch reaches the GUI capture instead of a display list.
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            for (ModelBox box : this.cubeList) {
                box.render(buffer, this.vulkanmod$scale);
            }
        } else {
            GlStateManager.callList(list);
        }
    }

    @Inject(method = "compileDisplayList(F)V", at = @At("HEAD"))
    private void vulkanmod$compileStart(float scale, CallbackInfo ci) {
        EntityRenderer.setCompiling(true);
    }

    @Inject(method = "compileDisplayList(F)V", at = @At("RETURN"))
    private void vulkanmod$compileEnd(float scale, CallbackInfo ci) {
        EntityRenderer.setCompiling(false);
    }
}
