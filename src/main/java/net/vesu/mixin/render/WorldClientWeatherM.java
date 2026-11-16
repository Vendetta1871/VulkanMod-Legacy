package net.vesu.mixin.render;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/** Each ambient block sample vanilla takes per tick also feeds the weather block-face spawner. */
@Mixin(WorldClient.class)
public class WorldClientWeatherM {

    @Inject(method = "showBarrierParticles", at = @At("TAIL"))
    private void vesu$weatherSample(int x, int y, int z, int offset, Random random, boolean holdingBarrier,
                                    BlockPos.MutableBlockPos pos, CallbackInfo ci) {
        net.vesu.weather.Spawner.onAmbientSample((WorldClient) (Object) this, pos);
    }
}
