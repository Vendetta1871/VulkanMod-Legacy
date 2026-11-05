package net.vesu.mixin.render;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.vesu.Initializer;
import net.vesu.render.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brackets {@code ParticleManager.renderParticles} so {@code WorldVertexBufferUploaderM}
 * replays the billboard batches (PARTICLE_POSITION_TEX_COLOR_LMAP) through the particle
 * pipeline. The pass runs inside {@code RenderGlobal.renderEntities}, after all entities,
 * while the world's Vulkan frame is still recording.
 *
 * <p>Also the hooks of {@link net.vesu.render.ParticleCulling}: particles outside the frustum
 * are not tessellated, nor block-break particles spawned out of view, the light cache starts
 * over every tick, and per-type spawn chances apply.</p>
 */
@Mixin(ParticleManager.class)
public class ParticleManagerM {

    @Inject(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"))
    private void vesu$beginParticles(Entity entity, float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            // F2: particles and translucent geometry are what the reactive mask marks.
            net.vesu.vulkan.Drawer.snapshotOpaque();
            EntityRenderer.setParticlePass(true);
            net.vesu.render.GpuParticles.begin();
        }
    }

    @Inject(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V", at = @At("RETURN"))
    private void vesu$endParticles(Entity entity, float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            net.vesu.render.GpuParticles.end();
            EntityRenderer.setParticlePass(false);
        }
    }

    @Redirect(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;renderParticle(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void vesu$renderVisible(Particle particle, BufferBuilder buffer, Entity entity, float partialTicks,
                                    float rotX, float rotZ, float rotYZ, float rotXY, float rotXZ) {
        if (net.vesu.render.ParticleCulling.visible(particle)) {
            particle.renderParticle(buffer, entity, partialTicks, rotX, rotZ, rotYZ, rotXY, rotXZ);
        }
    }

    @Inject(method = "updateEffects", at = @At("HEAD"))
    private void vesu$newTick(CallbackInfo ci) {
        net.vesu.render.ParticleCulling.newTick();
    }

    @Inject(method = "addBlockDestroyEffects", at = @At("HEAD"), cancellable = true)
    private void vesu$breakParticles(BlockPos pos, IBlockState state, CallbackInfo ci) {
        if (!net.vesu.render.ParticleCulling.breakParticles(pos)) ci.cancel();
    }

    @Inject(method = "spawnEffectParticle", at = @At("HEAD"), cancellable = true)
    private void vesu$spawnChance(int particleId, double x, double y, double z, double vx, double vy, double vz, int[] parameters,
                                  CallbackInfoReturnable<Particle> cir) {
        if (!net.vesu.render.ParticleCulling.spawn(particleId)) cir.setReturnValue(null);
    }

    // A texture layer's CPU quads, then its GPU particle records with the same state.
    @Redirect(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/Tessellator;draw()V"))
    private void vesu$drawLayer(net.minecraft.client.renderer.Tessellator tessellator) {
        tessellator.draw();
        net.vesu.render.GpuParticles.flush();
    }
}
