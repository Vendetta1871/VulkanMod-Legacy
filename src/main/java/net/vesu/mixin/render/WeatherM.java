package net.vesu.mixin.render;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.EnumParticleTypes;
import net.vesu.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Precipitation setting. Off: no rain or snow drawn and no splashes; the rain still sounds.
 * Particle: the vanilla rain/snow columns and splashes give way to the particle weather
 * ({@link net.vesu.weather.Weather}); the rain still sounds.
 */
@Mixin(EntityRenderer.class)
public class WeatherM {

    @Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
    private void vesu$precipitation(float partialTicks, CallbackInfo ci) {
        if (!"fancy".equals(Initializer.CONFIG.effectivePrecipitation())) ci.cancel();
    }

    @Redirect(method = "addRainParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;spawnParticle(Lnet/minecraft/util/EnumParticleTypes;DDDDDD[I)V"))
    private void vesu$splashes(WorldClient world, EnumParticleTypes type, double x, double y, double z,
                               double dx, double dy, double dz, int[] args) {
        if ("fancy".equals(Initializer.CONFIG.effectivePrecipitation())) world.spawnParticle(type, x, y, z, dx, dy, dz, args);
    }
}
