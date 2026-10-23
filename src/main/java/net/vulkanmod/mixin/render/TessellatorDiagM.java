package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.Tessellator;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEMP diagnostics: check whether Tessellator.draw runs during the GUI pass. */
@Mixin(Tessellator.class)
public class TessellatorDiagM {

    @Inject(method = "draw", at = @At("HEAD"))
    private void vulkanmod$diag(CallbackInfo ci) {
        if (Initializer.isVulkanActive() && GuiRenderer.diagAlways()) {
            Initializer.LOGGER.info("[gui] TESSELLATOR DRAW seen (guiPass={})", GuiRenderer.isGuiPass());
        }
        if (Initializer.isVulkanActive() && GuiRenderer.isGuiPass() && GuiRenderer.diagTess()) {
            try {
                java.lang.reflect.Field f = null;
                for (java.lang.reflect.Field cand : Tessellator.class.getDeclaredFields()) {
                    if (Tessellator.class.getName().equals(cand.getType().getName()) || cand.getType().isInterface()) {
                        continue;
                    }
                    if (cand.getType().getName().toLowerCase().contains("uploader")) {
                        f = cand;
                        break;
                    }
                }
                if (f == null) {
                    f = Tessellator.class.getDeclaredFields()[0];
                }
                f.setAccessible(true);
                Object uploader = f.get(this);
                if (GuiRenderer.diagTess() == true && GuiRenderer.firstTess()) {
                    StringBuilder sb = new StringBuilder();
                    for (java.lang.reflect.Field cand : Tessellator.class.getDeclaredFields()) {
                        sb.append(cand.getName()).append(':').append(cand.getType().getSimpleName()).append(' ');
                    }
                    Initializer.LOGGER.info("[gui] tessellator fields: {}", sb);
                }
                Initializer.LOGGER.info("[gui] tess draw: uploader={} verts={} fmt={} mode={}",
                        uploader.getClass().getName(),
                        ((Tessellator) (Object) this).getBuffer().getVertexCount(),
                        ((Tessellator) (Object) this).getBuffer().getVertexFormat(),
                        ((Tessellator) (Object) this).getBuffer().getDrawMode());
            } catch (Throwable t) {
                Initializer.LOGGER.info("[gui] tess draw diag failed: {}", t.toString());
            }
        }
    }
}
