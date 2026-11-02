package net.vulkanmod.render;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumUsage;
import net.vulkanmod.Initializer;
import net.vulkanmod.pack.plan.FramePlan;
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
    private static final VertexFormat BASE_FORMAT = new VertexFormat(DefaultVertexFormats.BLOCK)
            .addElement(new VertexFormatElement(0, EnumType.FLOAT, EnumUsage.COLOR, 1));
    public static VertexFormat FORMAT = BASE_FORMAT;
    public static int STRIDE = FORMAT.getSize();
    public static final int BLOCK_STRIDE = DefaultVertexFormats.BLOCK.getSize();

    /** {@code FramePlan.VERTEX_*} attributes of the vertices in the arena: 0 without a pack that asks. */
    private static int vertexFlags;
    private static int wantedFlags;
    /** Read while blocks are tessellated: OptiFine's {@code oldLighting=false} and {@code separateAo}. */
    public static volatile boolean plainShade, separateAo;
    /** Read on the chunk worker threads, see BlockRendererDispatcherM. */
    private static volatile boolean packBlockIds;

    /** Upper bound on simultaneously loaded sections (slot space) and visible sections/frame. */
    private static final int MAX_SLOTS = 16384;
    private static final int MAX_COMMANDS = 16384;
    /** A pack's shadow pass uses the upper half of the matrix and indirect buffers (GeometryPrelude). */
    private static final int SHADOW_SLOT_BASE = net.vulkanmod.pack.host.GeometryPrelude.SHADOW_SLOT_BASE;
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

        ssboSize = 2 * MAX_SLOTS * MATRIX_BYTES;
        int indirectSize = 2 * MAX_COMMANDS * CMD_BYTES;

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

    public static int vertexFlags() {
        return vertexFlags;
    }

    public static boolean packBlockIds() {
        return packBlockIds;
    }

    /** The attributes the frame's pack needs; applied by {@link #applyVertexFlags} at the frame boundary. */
    public static void requestVertexFlags(int flags) {
        wantedFlags = flags;
    }

    /**
     * Between frames: changes the vertex format of the arena when the pack asked for another
     * one. All chunks are rebuilt, as for a render distance change; without a pack nothing grows.
     */
    public static void applyVertexFlags() {
        if (wantedFlags == vertexFlags || arena == null) return;
        vertexFlags = wantedFlags;
        VertexFormat format = BASE_FORMAT;
        if (vertexFlags != 0) {
            format = new VertexFormat(BASE_FORMAT);
            if ((vertexFlags & FramePlan.VERTEX_NORMAL) != 0) format.addElement(new VertexFormatElement(0, EnumType.BYTE, EnumUsage.GENERIC, 4));
            if ((vertexFlags & FramePlan.VERTEX_TANGENT) != 0) format.addElement(new VertexFormatElement(0, EnumType.BYTE, EnumUsage.GENERIC, 4));
            if ((vertexFlags & FramePlan.VERTEX_MID_TEX) != 0) format.addElement(new VertexFormatElement(0, EnumType.USHORT, EnumUsage.GENERIC, 2));
            if ((vertexFlags & FramePlan.VERTEX_BLOCK) != 0) format.addElement(new VertexFormatElement(2, EnumType.USHORT, EnumUsage.UV, 2));
            if ((vertexFlags & FramePlan.VERTEX_MID_BLOCK) != 0) format.addElement(new VertexFormatElement(0, EnumType.BYTE, EnumUsage.GENERIC, 4));
        }
        FORMAT = format;
        STRIDE = format.getSize();
        arena.reset(STRIDE);
        pipeline.setVertexFormat(format);
        packBlockIds = (vertexFlags & (FramePlan.VERTEX_BLOCK | FramePlan.VERTEX_MID_BLOCK)) != 0;
        plainShade = (vertexFlags & FramePlan.VERTEX_PLAIN_SHADE) != 0;
        separateAo = (vertexFlags & FramePlan.VERTEX_SEPARATE_AO) != 0;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.renderGlobal != null) mc.renderGlobal.loadRenderers();
        Initializer.LOGGER.info("[batch] chunk vertices: stride={}B attributes={}", STRIDE, vertexFlags);
    }

    /** Allocates a persistent slot for a newly uploaded section. */
    public static int allocSlot() {
        arenaAdded++;
        Integer slot = instance.freeSlots.pollFirst();
        if (slot != null) {
            return slot;
        }
        return instance.nextSlot++;
    }

    /** Releases a section's slot on unload. Safe to reuse immediately: the SSBO is per-frame. */
    public static void freeSlot(int slot) {
        arenaVersion++;
        instance.freeSlots.addLast(slot);
    }

    /** Called at the start of each Vulkan frame; resets cursors and points at this frame's buffers. */
    public static void beginFrame() {
        curFrame = Drawer.getCurrentFrame();
        arena.tick();
        indirectCount = 0;
        shadowCommands = 0;
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

        long ssbo = MemoryUtil.memAddress0(ssboMaps[curFrame]) + (long) slot * MATRIX_BYTES;
        if (TerrainRenderer.NOQUERY) {
            MemoryUtil.memCopy(MemoryUtil.memAddress0(VRenderSystem.MVP), ssbo, 64);
            MemoryUtil.memCopy(MemoryUtil.memAddress0(VRenderSystem.modelViewMatrix), ssbo + 64, 64);
        } else {
            TerrainRenderer.writeCurrentSectionMatrices(ssbo);
        }

        long cmd = MemoryUtil.memAddress0(indirectMaps[curFrame]) + (long) indirectCount * CMD_BYTES;
        MemoryUtil.memPutInt(cmd, vertexCount * 3 / 2); // indexCount
        MemoryUtil.memPutInt(cmd + 4, 1);               // instanceCount
        MemoryUtil.memPutInt(cmd + 8, 0);               // firstIndex
        MemoryUtil.memPutInt(cmd + 12, vertexOffset);   // vertexOffset (vertices)
        MemoryUtil.memPutInt(cmd + 16, 0);              // firstInstance (unused; slot is per-vertex)

        ++indirectCount;
        ++layerCount;
        if (vertexCount > layerMaxVerts) {
            layerMaxVerts = vertexCount;
        }
        return true;
    }

    private static final org.joml.Matrix4f shadowMatrix = new org.joml.Matrix4f();
    private static final float[] shadowTemplate = new float[32];

    private static final ShadowSections[] shadowSections = new ShadowSections[4];
    /** Bumped when a section loses its place in the arena, and when one gets it. */
    private static int arenaVersion, arenaAdded;

    /**
     * A layer's sections in the light's box as flat arrays. Walking thousands of RenderChunks for
     * every layer of every frame is what the shadow pass spends its CPU on, and the set barely
     * changes: it is collected again when the arena changes, the camera moved or some time passed.
     */
    private static final class ShadowSections {
        private static final int MAX_AGE = 32;
        private static final double MAX_MOVE = 8.0;
        int count, version = -1, added, age;
        double camX, camY, camZ;
        int[] slot = new int[1024], firstVertex = new int[1024], vertices = new int[1024], x = new int[1024], y = new int[1024], z = new int[1024];

        boolean stale(int arena, double cx, double cy, double cz) {
            // What was freed must go now, its arena space gets reused. New sections can wait.
            return arena != version || ++age >= MAX_AGE || (arenaAdded != added && age >= 8) || Math.abs(cx - camX) + Math.abs(cy - camY) + Math.abs(cz - camZ) > MAX_MOVE;
        }

        void collect(java.util.List<net.minecraft.client.renderer.chunk.RenderChunk> sections,
                     net.minecraft.util.BlockRenderLayer layer, int arena, double cx, double cy, double cz) {
            version = arena;
            added = arenaAdded;
            age = 0;
            camX = cx;
            camY = cy;
            camZ = cz;
            count = 0;
            for (net.minecraft.client.renderer.chunk.RenderChunk chunk : sections) {
                if (chunk.getCompiledChunk().isLayerEmpty(layer)) continue;
                if (!(chunk.getVertexBufferByLayer(layer.ordinal()) instanceof IVboHolder holder)) continue;
                VBO vbo = holder.vulkanmod$getVbo();
                if (vbo == null || !vbo.inArena() || vbo.slot() >= MAX_SLOTS) continue;
                if (count == slot.length) grow();
                net.minecraft.util.math.BlockPos p = chunk.getPosition();
                slot[count] = vbo.slot();
                firstVertex[count] = vbo.arenaVertex();
                vertices[count] = vbo.vertexCount();
                x[count] = p.getX();
                y[count] = p.getY();
                z[count] = p.getZ();
                count++;
            }
        }

        private void grow() {
            int n = count * 2;
            slot = java.util.Arrays.copyOf(slot, n);
            firstVertex = java.util.Arrays.copyOf(firstVertex, n);
            vertices = java.util.Arrays.copyOf(vertices, n);
            x = java.util.Arrays.copyOf(x, n);
            y = java.util.Arrays.copyOf(y, n);
            z = java.util.Arrays.copyOf(z, n);
        }
    }

    /** Stores {@code template * translate(dx, dy, dz)}. */
    private static void writeTranslated(long address, int t, float dx, float dy, float dz) {
        float[] m = shadowTemplate;
        for (int i = 0; i < 12; i++) org.lwjgl.system.MemoryUtil.memPutFloat(address + i * 4L, m[t + i]);
        for (int i = 0; i < 4; i++) {
            org.lwjgl.system.MemoryUtil.memPutFloat(address + 48L + i * 4L,
                    m[t + i] * dx + m[t + 4 + i] * dy + m[t + 8 + i] * dz + m[t + 12 + i]);
        }
    }
    private static int shadowCommands;

    /** The layer in the shadow map: every resident section in the light's box, as one indirect draw. */
    private static void recordShadowLayer(net.vulkanmod.vulkan.ShadowPass shadow) {
        net.minecraft.util.BlockRenderLayer layer = TerrainRenderer.currentLayer;
        if (layer == null || !shadow.drawsLayer(layer) || pipeline.packPrograms == null) return;
        org.joml.Matrix4f clip = net.vulkanmod.shader.FrameUniforms.shadowProjectionRemapped();
        org.joml.Matrix4f view = net.vulkanmod.shader.FrameUniforms.shadowView();
        // Thousands of sections share everything but the translation column.
        clip.mul(view, shadowMatrix);
        shadowMatrix.get(shadowTemplate, 0);
        view.get(shadowTemplate, 16);
        long ssboAddress = org.lwjgl.system.MemoryUtil.memAddress0(ssboMaps[curFrame]);
        double camX = net.vulkanmod.shader.FrameUniforms.cameraX(), camY = net.vulkanmod.shader.FrameUniforms.cameraY(),
                camZ = net.vulkanmod.shader.FrameUniforms.cameraZ();
        ShadowSections list = shadowSections[layer.ordinal()];
        if (list == null) list = shadowSections[layer.ordinal()] = new ShadowSections();
        if (list.stale(arenaVersion, camX, camY, camZ) || shadow.sectionsPending()) {
            list.collect(shadow.sections(), layer, arenaVersion, camX, camY, camZ);
        }
        long indirectAddress = org.lwjgl.system.MemoryUtil.memAddress0(indirectMaps[curFrame]);
        int first = shadowCommands, count = Math.min(list.count, MAX_COMMANDS - first), maxVerts = 0;
        for (int i = 0; i < count; i++) {
            float dx = (float) (list.x[i] - camX), dy = (float) (list.y[i] - camY), dz = (float) (list.z[i] - camZ);
            long base = ssboAddress + (long) (list.slot[i] + SHADOW_SLOT_BASE) * MATRIX_BYTES;
            writeTranslated(base, 0, dx, dy, dz);
            writeTranslated(base + 64, 16, dx, dy, dz);
            long cmd = indirectAddress + (long) (MAX_COMMANDS + first + i) * CMD_BYTES;
            int vertices = list.vertices[i];
            org.lwjgl.system.MemoryUtil.memPutInt(cmd, vertices * 3 / 2);
            org.lwjgl.system.MemoryUtil.memPutInt(cmd + 4, 1);
            org.lwjgl.system.MemoryUtil.memPutInt(cmd + 8, 0);
            org.lwjgl.system.MemoryUtil.memPutInt(cmd + 12, list.firstVertex[i]);
            org.lwjgl.system.MemoryUtil.memPutInt(cmd + 16, 0);
            if (vertices > maxVerts) maxVerts = vertices;
        }
        shadowCommands = first + count;
        if (count == 0) return;
        Drawer drawer = Drawer.getInstance();
        IndexBuffer indexBuffer = drawer.getQuadsIndexBuffer().getIndexBuffer(maxVerts);
        shadow.enter();
        try {
            drawer.bindPipeline(pipeline);
            drawer.uploadAndBindUBOs(pipeline);
            drawer.drawIndexedIndirect(arena.bufferId(), indexBuffer, indirectBuffers[curFrame],
                    (long) (MAX_COMMANDS + first) * CMD_BYTES, count);
        } finally {
            shadow.leave();
        }
    }

    /** {@code backFace.<layer>=true} in shaders.properties. */
    private static boolean showsBackFaces(net.minecraft.util.BlockRenderLayer layer) {
        return net.vulkanmod.shader.PackManager.rendererIsTrue(switch (layer) {
            case SOLID -> "backFace.solid";
            case CUTOUT -> "backFace.cutout";
            case CUTOUT_MIPPED -> "backFace.cutoutMipped";
            case TRANSLUCENT -> "backFace.translucent";
        });
    }

    /** Called at the end of each chunk-layer pass; submits the layer as one indirect draw. */
    public static void endLayer() {
        if (net.vulkanmod.vulkan.ShadowPass.active()) recordShadowLayer(net.vulkanmod.vulkan.ShadowPass.recording());
        if (layerCount == 0) {
            return;
        }

        Drawer drawer = Drawer.getInstance();
        AutoIndexBuffer autoIndex = drawer.getQuadsIndexBuffer();
        IndexBuffer indexBuffer = autoIndex.getIndexBuffer(layerMaxVerts);

        boolean cull = VRenderSystem.cull;
        if (showsBackFaces(TerrainRenderer.currentLayer)) VRenderSystem.cull = false;
        drawer.bindPipeline(pipeline);
        VRenderSystem.cull = cull;
        drawer.uploadAndBindUBOs(pipeline);
        drawer.drawIndexedIndirect(arena.bufferId(), indexBuffer,
                indirectBuffers[curFrame], (long) layerStart * CMD_BYTES, layerCount);
    }

    public static ChunkArena arena() {
        return arena;
    }

    /**
     * World change (see MinecraftM): the previous world's sections were dropped without
     * freeing their arena ranges, so hand the whole arena back (M22).
     */
    public static void onWorldChange() {
        if (arena != null) {
            arena.reset();
        }
    }
}
