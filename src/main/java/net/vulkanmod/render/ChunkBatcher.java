package net.vulkanmod.render;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumUsage;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.AutoIndexBuffer;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.shader.SSBOResources;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Drives the batched chunk path. Every chunk section is uploaded into a shared vertex arena
 * carrying a per-vertex "slot" attribute; a storage buffer holds one {@code (MVP, ModelView)}
 * pair per slot. Each frame the game walks the visible-chunk list and this class appends one
 * {@code VkDrawIndexedIndirectCommand} per visible section; at the end of each render layer a
 * single {@code vkCmdDrawIndexedIndirect} submits the whole layer, collapsing hundreds of
 * per-chunk draws (and their per-draw descriptor sets) into one call.
 *
 * <p>The matrix SSBO and the indirect buffer are kept per swapchain image (like the UBO ring):
 * the in-flight fence at frame start only guards reuse of the same image's resources, so a
 * single shared buffer would be overwritten by frame N+1 while frame N is still executing.
 * Slots are persistent per section (allocated on upload, released on unload) and unique within
 * a frame, so the per-frame SSBO always holds the right matrix for every drawn section.</p>
 */
public class ChunkBatcher {

    /** BLOCK plus a trailing float carrying the section slot (location 4, R32_SFLOAT). */
    public static final VertexFormat FORMAT = new VertexFormat(DefaultVertexFormats.BLOCK)
            .addElement(new VertexFormatElement(0, EnumType.FLOAT, EnumUsage.COLOR, 1));
    public static final int STRIDE = FORMAT.getSize();
    public static final int BLOCK_STRIDE = DefaultVertexFormats.BLOCK.getSize();

    /** Upper bound on simultaneously loaded sections (slot space) and visible sections/frame. */
    private static final int MAX_SLOTS = 16384;
    private static final int MAX_COMMANDS = 16384;
    private static final int MATRIX_BYTES = 128;   // two column-major mat4
    private static final int CMD_BYTES = 20;       // VkDrawIndexedIndirectCommand (5 x u32)

    private static ChunkArena arena;
    private static Pipeline pipeline;

    private static int frames;
    private static long[] ssboBuffers, indirectBuffers;
    private static ByteBuffer[] ssboMaps, indirectMaps;
    private static int ssboSize;
    private static int curFrame;

    // Persistent slot allocator (compact indices into the matrix SSBO).
    private final Deque<Integer> freeSlots = new ArrayDeque<>();
    private int nextSlot;

    // Per-frame recording state.
    private static int indirectCount;
    // Per-layer recording state.
    private static int layerStart;
    private static int layerCount;
    private static int layerMaxVerts;

    private static ChunkBatcher instance;
    private static boolean overflowWarned;

    public static void init(ChunkArena chunkArena, Pipeline batchedPipeline) {
        instance = new ChunkBatcher();
        arena = chunkArena;
        pipeline = batchedPipeline;
        frames = Vulkan.getSwapChainImages().size();

        ssboSize = MAX_SLOTS * MATRIX_BYTES;
        int indirectSize = MAX_COMMANDS * CMD_BYTES;

        ssboBuffers = new long[frames];
        indirectBuffers = new long[frames];
        ssboMaps = new ByteBuffer[frames];
        indirectMaps = new ByteBuffer[frames];

        final int hostFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;

        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pMem = stack.pointers(VK_NULL_HANDLE);

            for (int f = 0; f < frames; ++f) {
                MemoryManager.createBuffer(ssboSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags, pBuf, pMem);
                ssboBuffers[f] = pBuf.get(0);
                ssboMaps[f] = MemoryUtil.memByteBuffer(MemoryManager.Map(pMem.get(0)).get(0), ssboSize);

                MemoryManager.createBuffer(indirectSize, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, hostFlags, pBuf, pMem);
                indirectBuffers[f] = pBuf.get(0);
                indirectMaps[f] = MemoryUtil.memByteBuffer(MemoryManager.Map(pMem.get(0)).get(0), indirectSize);
            }
        }

        Initializer.LOGGER.info("[batch] enabled: arena={}MB stride={}B, {} frame buffers of ssbo={}KB indirect={}KB",
                arena.capacity() / (1024 * 1024), STRIDE, frames, ssboSize / 1024, indirectSize / 1024);
    }

    /** Allocates a persistent slot for a newly uploaded section. */
    public static int allocSlot() {
        Integer slot = instance.freeSlots.pollFirst();
        if (slot != null) {
            return slot;
        }
        return instance.nextSlot++;
    }

    /** Releases a section's slot on unload. Safe to reuse immediately: the SSBO is per-frame. */
    public static void freeSlot(int slot) {
        instance.freeSlots.addLast(slot);
    }

    /** Called at the start of each Vulkan frame; resets cursors and points at this frame's buffers. */
    public static void beginFrame() {
        curFrame = Drawer.getCurrentFrame();
        arena.tick();
        indirectCount = 0;
        // The terrain_batch descriptor resolves its SSBO by name; bind this frame's buffer.
        SSBOResources.bind("ChunkMatrices", ssboBuffers[curFrame], 0, ssboSize);
    }

    /** Called at the start of each chunk-layer pass. */
    public static void beginLayer() {
        layerStart = indirectCount;
        layerCount = 0;
        layerMaxVerts = 0;
    }

    /**
     * Records one visible section: writes its matrices to its slot in the SSBO and appends an
     * indirect command that draws its arena slice.
     *
     * @param slot         the section's persistent slot (matches its baked vertex attribute)
     * @param vertexOffset the section's byte offset in the arena divided by the vertex stride
     * @param vertexCount  the section's vertex count (index count is {@code *3/2} for quads)
     */
    public static boolean record(int slot, int vertexOffset, int vertexCount) {
        if (indirectCount >= MAX_COMMANDS) {
            if (!overflowWarned) {
                overflowWarned = true;
                Initializer.LOGGER.warn("[batch] command overflow (>{}), some chunks skipped", MAX_COMMANDS);
            }
            return false;
        }
        if (slot >= MAX_SLOTS) {
            if (!overflowWarned) {
                overflowWarned = true;
                Initializer.LOGGER.warn("[batch] SLOT overflow (slot {} >= {}), matrices out of SSBO", slot, MAX_SLOTS);
            }
            return false;
        }

        ByteBuffer ssbo = ssboMaps[curFrame];
        ByteBuffer indirect = indirectMaps[curFrame];

        ByteBuffer mvp = VRenderSystem.MVP;
        ByteBuffer mv = VRenderSystem.modelViewMatrix;
        int base = slot * MATRIX_BYTES;
        for (int k = 0; k < 16; ++k) {
            ssbo.putFloat(base + k * 4, mvp.getFloat(k * 4));
            ssbo.putFloat(base + 64 + k * 4, mv.getFloat(k * 4));
        }

        int cmd = indirectCount * CMD_BYTES;
        indirect.putInt(cmd, vertexCount * 3 / 2); // indexCount
        indirect.putInt(cmd + 4, 1);               // instanceCount
        indirect.putInt(cmd + 8, 0);               // firstIndex
        indirect.putInt(cmd + 12, vertexOffset);   // vertexOffset (vertices)
        indirect.putInt(cmd + 16, 0);              // firstInstance (unused; slot is per-vertex)

        ++indirectCount;
        ++layerCount;
        if (vertexCount > layerMaxVerts) {
            layerMaxVerts = vertexCount;
        }
        return true;
    }

    /** Called at the end of each chunk-layer pass; submits the layer as one indirect draw. */
    public static void endLayer() {
        if (layerCount == 0) {
            return;
        }

        Drawer drawer = Drawer.getInstance();
        AutoIndexBuffer autoIndex = drawer.getQuadsIndexBuffer();
        autoIndex.checkCapacity(layerMaxVerts);
        IndexBuffer indexBuffer = autoIndex.getIndexBuffer();

        drawer.bindPipeline(pipeline);
        drawer.uploadAndBindUBOs(pipeline);
        drawer.drawIndexedIndirect(arena.bufferId(), indexBuffer,
                indirectBuffers[curFrame], (long) layerStart * CMD_BYTES, layerCount);
    }

    public static ChunkArena arena() {
        return arena;
    }
}
