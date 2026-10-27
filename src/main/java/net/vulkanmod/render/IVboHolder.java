package net.vulkanmod.render;

/** Implemented by the VertexBuffer mixin: the Vulkan VBO behind a vanilla VertexBuffer. */
public interface IVboHolder {
    VBO vulkanmod$getVbo();
}
