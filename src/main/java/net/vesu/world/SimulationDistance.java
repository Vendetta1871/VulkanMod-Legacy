package net.vesu.world;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.vesu.Initializer;

/**
 * Simulation distance for the integrated server, as in newer Minecraft: entities, block
 * entities, scheduled and random ticks only run within this many chunks of a player.
 * Chunks farther away, up to the render distance, stay loaded and visible but frozen.
 * Chunks kept loaded by a mod (Forge chunk tickets) always tick. The mixins that consult
 * this are client-side only, so a dedicated server is never affected.
 */
public final class SimulationDistance {

    /** True when the chunk at {@code chunkX, chunkZ} is simulated. */
    public static boolean ticks(World world, int chunkX, int chunkZ) {
        if (world.isRemote || Initializer.CONFIG == null) return true;
        int limit = Initializer.CONFIG.effectiveSimulationDistance();
        for (EntityPlayer player : world.playerEntities) {
            int px = net.minecraft.util.math.MathHelper.floor(player.posX) >> 4;
            int pz = net.minecraft.util.math.MathHelper.floor(player.posZ) >> 4;
            if (Math.abs(px - chunkX) <= limit && Math.abs(pz - chunkZ) <= limit) return true;
        }
        return !world.getPersistentChunks().isEmpty()
                && world.getPersistentChunks().containsKey(new ChunkPos(chunkX, chunkZ));
    }

    public static boolean ticksBlock(World world, net.minecraft.util.math.BlockPos pos) {
        return ticks(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    private SimulationDistance() {
    }
}
