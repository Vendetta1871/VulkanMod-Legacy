package net.vesu.mixin.render;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.vesu.render.ChunkBatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Tags chunk vertices with their block for a pack that asked for {@code blockId}. The chunk
 * format has no room for it, but the lightmap coordinates only use the low byte of UV2, and
 * the tag has to stay with the vertex when the translucent layer is sorted. {@code VBO.extend}
 * moves it to its own attribute at upload. Runs on the chunk worker threads.
 */
@Mixin(BlockRendererDispatcher.class)
public class BlockRendererDispatcherM {

    private static final int STRIDE = 28, UV2_OFFSET = 24;
    private static final ThreadLocal<int[]> FIRST_VERTEX = ThreadLocal.withInitial(() -> new int[1]);

    @Inject(method = "renderBlock", at = @At("HEAD"))
    private void vesu$blockStart(IBlockState state, BlockPos pos, IBlockAccess world, BufferBuilder buffer,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (ChunkBatcher.packBlockIds()) FIRST_VERTEX.get()[0] = buffer.getVertexCount();
    }

    @Inject(method = "renderBlock", at = @At("RETURN"))
    private void vesu$blockEnd(IBlockState state, BlockPos pos, IBlockAccess world, BufferBuilder buffer,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (!ChunkBatcher.packBlockIds()) return;
        int id = Block.getIdFromBlock(state.getBlock()) & 0xFFF;
        int meta = state.getBlock().getMetaFromState(state) & 0xF;
        ByteBuffer bytes = buffer.getByteBuffer();
        for (int v = FIRST_VERTEX.get()[0]; v < buffer.getVertexCount(); v++) {
            int at = v * STRIDE + UV2_OFFSET;
            bytes.putShort(at, (short) ((bytes.getShort(at) & 0xFF) | (id & 0xFF) << 8));
            bytes.putShort(at + 2, (short) ((bytes.getShort(at + 2) & 0xFF) | id >> 8 << 8 | meta << 12));
        }
    }
}
