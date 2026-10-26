package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.OpenGlHelper;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Vulkan renderer always uses vertex buffers with the 3-attribute format
 * (position, texture, brightness), which is what vanilla selects when VBOs are
 * enabled. Force {@code useVbo()} on so entity and world renderers never take
 * the display-list path, whose vertex format lacks the brightness attribute.
 */
@Mixin(OpenGlHelper.class)
public class OpenGlHelperM {

	@Inject(method = "useVbo()Z", at = @At("HEAD"), cancellable = true)
	private static void forceVbo(CallbackInfoReturnable<Boolean> cir) {
		if(Initializer.isVulkanActive()) {
			cir.setReturnValue(true);
		}
	}
}
