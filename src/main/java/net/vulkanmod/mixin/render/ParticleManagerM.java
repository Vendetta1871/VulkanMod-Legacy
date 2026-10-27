package net.vulkanmod.mixin.render;

import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets {@code ParticleManager.renderParticles} so {@code WorldVertexBufferUploaderM}
 * replays the billboard batches (PARTICLE_POSITION_TEX_COLOR_LMAP) through the particle
 * pipeline. The pass runs inside {@code RenderGlobal.renderEntities}, after all entities,
 * while the world's Vulkan frame is still recording.
 */
@Mixin(ParticleManager.class)
public class ParticleManagerM {

    @Inject(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"))
    private void vulkanmod$beginParticles(Entity entity, float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            // F2: particles and translucent geometry are what the reactive mask marks.
            net.vulkanmod.vulkan.Drawer.snapshotOpaque();
            EntityRenderer.setParticlePass(true);
        }
    }

    @Inject(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V", at = @At("RETURN"))
    private void vulkanmod$endParticles(Entity entity, float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            EntityRenderer.setParticlePass(false);
        }
    }
}
