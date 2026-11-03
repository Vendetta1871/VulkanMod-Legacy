package net.vesu.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LOD live ingest (AUDIT 5.6): a full chunk has its blocks and light. */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientM {
    @Inject(method = "handleChunkData", at = @At("TAIL"))
    private void vesu$chunkData(SPacketChunkData packet, CallbackInfo ci) {
        if (!packet.isFullChunk()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return;
        net.vesu.lod.mc.LodSystem.onChunkData(mc.world.getChunk(packet.getChunkX(), packet.getChunkZ()));
    }
}
