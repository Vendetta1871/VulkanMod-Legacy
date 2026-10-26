package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts matrix mutations (M11/N18). GuiRenderer caches the GUI projection and batches
 * font glyphs on the counter: while it holds, no matrix op ran, so matrices read earlier
 * are still current and the per-draw {@code glGetFloatv} pair can be skipped.
 *
 * <p>Vanilla GUI code drives every matrix op through GlStateManager - Gui, GuiScreen,
 * GuiContainer, GuiIngame, FontRenderer and RenderHelper contain no raw GL11 matrix
 * calls - so the counter is exact for the GUI pass. A mod mutating the matrix stack
 * through raw GL11 inside {@code drawScreen} would slip past it; the worst case is a
 * glyph drawn under a stale matrix.</p>
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMatrixM {

    @Inject(method = {"pushMatrix()V", "popMatrix()V", "loadIdentity()V",
            "translate(FFF)V", "translate(DDD)V",
            "rotate(FFFF)V", "rotate(Lorg/lwjgl/util/vector/Quaternion;)V",
            "scale(FFF)V", "scale(DDD)V",
            "ortho(DDDDDD)V", "multMatrix(Ljava/nio/FloatBuffer;)V"}, at = @At("HEAD"))
    private static void vulkanmod$bumpMatrixVersion(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.bumpMatrixVersion();
        }
    }
}
