package net.vesu.mixin.render;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.vesu.world.SimulationDistance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Simulation distance (see {@link SimulationDistance}): random and scheduled block ticks. */
@Mixin(WorldServer.class)
public abstract class SimulationWorldServerM {

    private static final ExtendedBlockStorage[] VESU$NO_SECTIONS = new ExtendedBlockStorage[0];

    /** Random ticks: a chunk beyond the distance offers no sections to tick. */
    @Redirect(method = "updateBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/Chunk;getBlockStorageArray()[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;"))
    private ExtendedBlockStorage[] vesu$randomTicks(Chunk chunk) {
        return SimulationDistance.ticks(chunk.getWorld(), chunk.x, chunk.z) ? chunk.getBlockStorageArray() : VESU$NO_SECTIONS;
    }

    /** Lightning strikes only simulated chunks. */
    @Redirect(method = "updateBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/WorldProvider;canDoLightning(Lnet/minecraft/world/chunk/Chunk;)Z"))
    private boolean vesu$lightning(WorldProvider provider, Chunk chunk) {
        return provider.canDoLightning(chunk) && SimulationDistance.ticks(chunk.getWorld(), chunk.x, chunk.z);
    }

    /** Snow and ice form only in simulated chunks. */
    @Redirect(method = "updateBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/WorldProvider;canDoRainSnowIce(Lnet/minecraft/world/chunk/Chunk;)Z"))
    private boolean vesu$snowAndIce(WorldProvider provider, Chunk chunk) {
        return provider.canDoRainSnowIce(chunk) && SimulationDistance.ticks(chunk.getWorld(), chunk.x, chunk.z);
    }

    /** A scheduled tick beyond the distance is treated as unloaded: vanilla schedules it again. */
    @Redirect(method = "tickUpdates", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/WorldServer;isAreaLoaded(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;)Z"))
    private boolean vesu$scheduledTicks(WorldServer world, BlockPos from, BlockPos to) {
        return world.isAreaLoaded(from, to) && SimulationDistance.ticksBlock(world, from);
    }
}
