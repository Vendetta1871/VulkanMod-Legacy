package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.entity.RenderDragon;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.DebugLinesRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the beam window for DebugLinesRenderer (R17). {@code renderCrystalBeams} is the
 * one static helper behind both the dragon's healing beam (RenderDragon.doRender) and the
 * End crystal beams (RenderEnderCrystal.doRender); its POSITION_TEX_COLOR strip is the
 * only textured strip the line replay should take.
 */
@Mixin(RenderDragon.class)
public class RenderDragonM {

    @Inject(method = "renderCrystalBeams(DDDFDDDIDDD)V", at = @At("HEAD"))
    private static void vulkanmod$beamsBegin(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            DebugLinesRenderer.setBeamWindow(true);
        }
    }

    @Inject(method = "renderCrystalBeams(DDDFDDDIDDD)V", at = @At("RETURN"))
    private static void vulkanmod$beamsEnd(CallbackInfo ci) {
        DebugLinesRenderer.setBeamWindow(false);
    }
}
