package net.vesu.vulkan;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * A pack program the LOD renderer draws distant terrain with (AUDIT 5.16, contract B: the
 * pack's {@code dh_terrain} or {@code dh_water}), compiled against the LOD host source. The
 * SPIR-V belongs to the pack runtime and is freed with it.
 */
public final class LodPackProgram {
    public final ByteBuffer vertex, fragment;
    /** Bit i: the program writes color attachment i. */
    public final int targetMask;
    /** The program's blending, or null for none; per attachment where the pack sets one. */
    public final Pipeline.BlendState blend;
    public final Pipeline.BlendState[] targetBlend;

    LodPackProgram(ByteBuffer vertex, ByteBuffer fragment, int targetMask, Pipeline.BlendState blend,
                   Pipeline.BlendState[] targetBlend) {
        this.vertex = copy(vertex);
        this.fragment = copy(fragment);
        this.targetMask = targetMask;
        this.blend = blend;
        this.targetBlend = targetBlend;
    }

    private static ByteBuffer copy(ByteBuffer src) {
        ByteBuffer b = MemoryUtil.memAlloc(src.remaining());
        b.put(src.duplicate()).flip();
        return b;
    }

    void free() {
        MemoryUtil.memFree(vertex);
        MemoryUtil.memFree(fragment);
    }
}
