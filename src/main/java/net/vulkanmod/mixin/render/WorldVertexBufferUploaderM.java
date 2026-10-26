package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.DebugLinesRenderer;
import net.vulkanmod.render.EndPortalRenderer;
import net.vulkanmod.render.EntityRenderer;
import net.vulkanmod.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures entity and GUI model batches as they are uploaded. When {@link EntityRenderer}
 * or {@link GuiRenderer} replays the batch in Vulkan the GL draw is skipped (the swapchain
 * is VulkanMod's, so it would not be seen anyway) but the buffer is still reset, keeping
 * the shared BufferBuilder consistent for the next caller.
 */
@Mixin(WorldVertexBufferUploader.class)
public class WorldVertexBufferUploaderM {

    @Inject(method = "draw(Lnet/minecraft/client/renderer/BufferBuilder;)V", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$capture(BufferBuilder buffer, CallbackInfo ci) {
        if (!Initializer.isVulkanActive()) {
            return;
        }
        // The End portal planes must be checked first: the TESR runs inside the entity
        // pass window, and its POSITION_COLOR quads would otherwise be swallowed by the
        // flat-colored entity path (no texture, no eye-linear texgen).
        boolean captured;
        if (EndPortalRenderer.accepts(buffer)) {
            EndPortalRenderer.render(buffer);
            captured = true;
        } else {
            captured = (EntityRenderer.isActive() && EntityRenderer.capture(buffer))
                    || (GuiRenderer.isGuiPass() && GuiRenderer.capture(buffer));
        }
        if (!captured && DebugLinesRenderer.accepts(buffer)) {
            // World-pass POSITION_COLOR lines: the F3 debug boxes and the block selection
            // box. Their glDrawArrays is never presented, so replay them in Vulkan.
            DebugLinesRenderer.render(buffer);
            captured = true;
        }
        if (captured) {
            buffer.reset();
            ci.cancel();
        }
    }
}
