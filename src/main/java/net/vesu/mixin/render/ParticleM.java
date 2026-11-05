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

    @Inject(method = "getBrightnessForRender(F)I", at = @At("HEAD"), cancellable = true)
    private void vesu$cachedLight(float partialTicks, CallbackInfoReturnable<Integer> cir) {
        int light = ParticleCulling.light(vesu$world(), vesu$posX(), vesu$posY(), vesu$posZ());
        if (light >= 0) cir.setReturnValue(light);
    }
}
