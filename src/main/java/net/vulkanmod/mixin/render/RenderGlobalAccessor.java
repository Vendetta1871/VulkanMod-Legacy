package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The render chunks already built, so turning ray tracing on can register them (RtScene). */
@Mixin(RenderGlobal.class)
public interface RenderGlobalAccessor {

    @Accessor("viewFrustum")
    ViewFrustum vulkanmod$getViewFrustum();

    @Accessor("renderDispatcher")
    net.minecraft.client.renderer.chunk.ChunkRenderDispatcher vulkanmod$getRenderDispatcher();

    @Accessor("renderInfos")
    java.util.List<?> vulkanmod$getRenderInfos();
}
