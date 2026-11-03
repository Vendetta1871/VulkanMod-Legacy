package net.vesu.mixin.render;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LOD live ingest (AUDIT 5.6): a chunk's last state before the client drops it. */
@Mixin(ChunkProviderClient.class)
public abstract class ChunkProviderClientM {
    @Inject(method = "unloadChunk", at = @At("HEAD"))
    private void vesu$unload(int x, int z, CallbackInfo ci) {
        net.vesu.lod.mc.LodSystem.onChunkUnload(((ChunkProviderClient) (Object) this).getLoadedChunk(x, z));
    }
}
