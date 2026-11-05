package net.vesu.mixin.render;

import net.minecraft.client.particle.Particle;
import net.minecraft.world.World;
import net.vesu.render.ParticleCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A particle's light comes from the per-tick cache of {@link ParticleCulling#light} instead of a
 * world lookup per particle and frame. Subclasses that brighten it (flame, portal) call this.
 */
@Mixin(Particle.class)
public abstract class ParticleM {

    // Accessors instead of @Shadow: shadowed fields would not bind under the SRG names.
    @Accessor("world") abstract World vesu$world();
    @Accessor("posX") abstract double vesu$posX();
    @Accessor("posY") abstract double vesu$posY();
    @Accessor("posZ") abstract double vesu$posZ();
    @Accessor("prevPosX") abstract double vesu$prevPosX();
    @Accessor("prevPosY") abstract double vesu$prevPosY();
    @Accessor("prevPosZ") abstract double vesu$prevPosZ();
    @Accessor("particleScale") abstract float vesu$scale();
    @Accessor("particleTexture") abstract net.minecraft.client.renderer.texture.TextureAtlasSprite vesu$sprite();
    @Accessor("particleTextureIndexX") abstract int vesu$indexX();
    @Accessor("particleTextureIndexY") abstract int vesu$indexY();
    @Accessor("particleRed") abstract float vesu$red();
    @Accessor("particleGreen") abstract float vesu$green();
    @Accessor("particleBlue") abstract float vesu$blue();
    @Accessor("particleAlpha") abstract float vesu$alpha();
    @Accessor("particleAngle") abstract float vesu$angle();
    @Accessor("prevParticleAngle") abstract float vesu$prevAngle();

    /** The base billboard as a GPU record ({@link net.vesu.render.GpuParticles}). */
    @Inject(method = "renderParticle", at = @At("HEAD"), cancellable = true)
    private void vesu$gpuQuad(net.minecraft.client.renderer.BufferBuilder buffer, net.minecraft.entity.Entity entity, float partialTicks,
                              float rotX, float rotZ, float rotYZ, float rotXY, float rotXZ,
                              org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!net.vesu.render.GpuParticles.accepting()) return;
        net.vesu.render.GpuParticles.add((Particle) (Object) this, partialTicks, rotX, rotZ, rotYZ, rotXY, rotXZ,
                vesu$posX(), vesu$posY(), vesu$posZ(), vesu$prevPosX(), vesu$prevPosY(), vesu$prevPosZ(), vesu$scale(),
                vesu$sprite(), vesu$indexX(), vesu$indexY(), vesu$red(), vesu$green(), vesu$blue(), vesu$alpha(),
                vesu$angle(), vesu$prevAngle());
        ci.cancel();
    }

    @Inject(method = "getBrightnessForRender(F)I", at = @At("HEAD"), cancellable = true)
    private void vesu$cachedLight(float partialTicks, CallbackInfoReturnable<Integer> cir) {
        int light = ParticleCulling.light(vesu$world(), vesu$posX(), vesu$posY(), vesu$posZ());
        if (light >= 0) cir.setReturnValue(light);
    }
}
