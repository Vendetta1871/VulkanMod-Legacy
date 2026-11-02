package net.vesu.mixin.render;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.vesu.world.SimulationDistance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Simulation distance (see {@link SimulationDistance}): entities and block entities. */
@Mixin(World.class)
public abstract class SimulationWorldM {

    /** A ticked (not a forced) update of an entity beyond the simulation distance is skipped. */
    @Inject(method = "updateEntityWithOptionalForce", at = @At("HEAD"), cancellable = true)
    private void vesu$simulateEntity(Entity entity, boolean forceUpdate, CallbackInfo ci) {
        World world = (World) (Object) this;
        if (forceUpdate && !world.isRemote && !(entity instanceof EntityPlayer)
                && !SimulationDistance.ticks(world, MathHelper.floor(entity.posX) >> 4, MathHelper.floor(entity.posZ) >> 4)) {
            ci.cancel();
        }
    }

    /** The tickable block entity loop treats a block entity beyond it as unloaded. */
    @Redirect(method = "updateEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;isBlockLoaded(Lnet/minecraft/util/math/BlockPos;Z)Z"))
    private boolean vesu$simulateBlockEntity(World world, BlockPos pos, boolean allowEmpty) {
        return world.isBlockLoaded(pos, allowEmpty) && SimulationDistance.ticksBlock(world, pos);
    }
}
