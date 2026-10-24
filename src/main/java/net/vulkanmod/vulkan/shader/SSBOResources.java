package net.vulkanmod.vulkan.shader;

import java.util.HashMap;
import java.util.Map;

/**
 * Global registry mapping the {@code SSBOs} names declared in a pipeline's JSON to the
 * storage buffer they should bind. Unlike UBOs (which live in the per-frame ring buffer and
 * are rewritten every draw), a storage buffer such as the chunk-matrix array is persistent,
 * so its descriptor is resolved by name once and reused.
 *
 * <p>The renderer registers each buffer here before the pipeline that references it builds
 * its descriptor sets; see {@code ChunkBatcher}.</p>
 */
public class SSBOResources {

    /** A resolved storage-buffer descriptor target. */
    public record Binding(long buffer, long offset, long range) {}

    private static final Map<String, Binding> BINDINGS = new HashMap<>();

    public static void bind(String name, long buffer, long offset, long range) {
        BINDINGS.put(name, new Binding(buffer, offset, range));
    }

    public static Binding get(String name) {
        return BINDINGS.get(name);
    }

    private SSBOResources() {
    }
}
