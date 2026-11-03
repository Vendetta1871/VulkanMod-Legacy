package net.vesu.lod.mc;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;

/** Block and light changes on the client world, re-ingested per section (AUDIT 5.6). */
final class LodWorldListener implements IWorldEventListener {
    @Override
    public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
        if (oldState != newState) LodSystem.onBlockChanged(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void notifyLightSet(BlockPos pos) {
        LodSystem.onBlockChanged(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    @Override
    public void playSoundToAllNearExcept(EntityPlayer player, SoundEvent sound, SoundCategory category, double x, double y, double z,
                                         float volume, float pitch) {
    }

    @Override
    public void playRecord(SoundEvent sound, BlockPos pos) {
    }

    @Override
    public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z, double xs, double ys, double zs, int... p) {
    }

    @Override
    public void spawnParticle(int id, boolean ignoreRange, boolean minimise, double x, double y, double z, double xs, double ys,
                              double zs, int... p) {
    }

    @Override
    public void onEntityAdded(Entity entity) {
    }

    @Override
    public void onEntityRemoved(Entity entity) {
    }

    @Override
    public void broadcastSound(int id, BlockPos pos, int data) {
    }

    @Override
    public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) {
    }

    @Override
    public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {
    }
}
