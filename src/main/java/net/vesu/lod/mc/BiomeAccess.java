package net.vesu.lod.mc;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;

/** A one-block world in one biome, for {@code BlockColors} (AUDIT 5.14: position 0). */
final class BiomeAccess implements IBlockAccess {
    Biome biome;
    IBlockState state;

    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        return 15 << 20 | 15 << 4;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return pos.getX() == 0 && pos.getY() == 0 && pos.getZ() == 0 ? state : Blocks.AIR.getDefaultState();
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return !(pos.getX() == 0 && pos.getY() == 0 && pos.getZ() == 0);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return biome;
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return 0;
    }

    @Override
    public WorldType getWorldType() {
        return WorldType.DEFAULT;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        return _default;
    }
}
