package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEMP diagnostics: find what actually issues GUI draw calls. */
@Mixin(GlStateManager.class)
public class GlDrawDiagM {

    @Inject(method = "glDrawArrays(III)V", at = @At("HEAD"))
    private static void vulkanmod$diag(int mode, int first, int count, CallbackInfo ci) {
        if (Initializer.isVulkanActive() && GuiRenderer.isGuiPass() && GuiRenderer.diagGlDraw()) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String c = e.getClassName();
                if (c.startsWith("net.minecraft") || c.startsWith("com.cleanroommc")
                        || c.startsWith("net.vulkanmod") || c.startsWith("org.lwjgl")) {
                    sb.append("\n    at ").append(e);
                }
            }
            Initializer.LOGGER.info("[gui] glDrawArrays mode={} count={}:{}", mode, count, sb);
        }
    }
}
