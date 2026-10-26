package net.vulkanmod.render;

import net.minecraft.client.renderer.chunk.RenderChunk;

import java.util.List;

/**
 * Implemented by the {@code ChunkRenderContainer} mixin so collaborators (the
 * {@code VboRenderList} mixin, whose target subclasses ChunkRenderContainer) can reach
 * the visible-chunk list, which is a private field on the superclass. Lives outside the
 * mixin package because mixin forbids referencing non-mixin classes defined there.
 */
public interface IRenderChunks {
    List<RenderChunk> vulkanmod$getRenderChunks();
}
