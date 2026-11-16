package net.vesu.mixin.render;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Position and horizontal velocity of a vanilla particle, for the weather wind (net.vesu.weather). */
@Mixin(Particle.class)
public interface ParticleMotionAccessor {
    @Accessor("posX") double vesu$wx();
    @Accessor("posY") double vesu$wy();
    @Accessor("posZ") double vesu$wz();
    @Accessor("motionX") double vesu$wmx();
    @Accessor("motionX") void vesu$setWmx(double v);
    @Accessor("motionZ") double vesu$wmz();
    @Accessor("motionZ") void vesu$setWmz(double v);
}
