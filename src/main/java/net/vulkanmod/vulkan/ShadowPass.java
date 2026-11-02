package net.vulkanmod.vulkan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.vulkanmod.Initializer;
import net.vulkanmod.debug.FrameProfiler;
import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.plan.FramePlan;
import net.vulkanmod.pack.plan.GeometryCategory;
import net.vulkanmod.render.ChunkBatcher;
import net.vulkanmod.render.EntityRenderer;
import net.vulkanmod.shader.FrameUniforms;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * The shadow map of a pack (runtime.md, "Shadow pass", D8). It is recorded while the frame's
 * world pass runs, into a command buffer of its own that {@link Drawer} submits before the
 * frame's main buffer, so the main pass can sample what is recorded alongside it.
 *
 * <p>Terrain comes from the chunk arena: at the end of each layer {@link ChunkBatcher} culls
 * the resident sections against the light's box and issues one indirect draw. An entity is
 * drawn again right after its main draw, with the shadow matrices and the vertices already in
 * the ring, so vanilla's entity code runs once. Entities the camera does not see, and the
 * first-person player, run in a shadow-only mode at the end of the entity pass.</p>
 */
public final class ShadowPass {

    /** Sections outside the camera's view handed to the chunk workers per frame. */
    private static final int BUILDS_PER_FRAME = 4;
    /** The entity being drawn can reach the shadow map. */
    private boolean entityInBox = true;
    private boolean buildsPending;

    /** Between {@link #open} and {@link #finish}: null without a pack that has shadows. */
    private static ShadowPass recording;
    /** Draws go to the shadow command buffer: {@link Drawer#bindPipeline} routes them here. */
    private static ShadowPass drawing;

    /** The cost of the check on the no-pack path is this null comparison. */
    public static boolean active() {
        return recording != null;
    }

    public static ShadowPass recording() {
        return recording;
    }

    static ShadowPass drawing() {
        return drawing;
    }

    private final FramePlan plan;
    private final IrPack.Shadow config;
    private final String id;
    private final long mrtBits;
    private final int[] colorFormats;
    private final PackRuntime.Image[] colors;
    private final long depthSampler, compareSampler, colorSampler;
    private final long integerSampler;
    private PackRuntime.Image depth0, depth1;
    private boolean allocated;

    private VkCommandBuffer cmd, mainCmd;
    private boolean translucent, depthCopied, sectionsReady;
    private final List<RenderChunk> sections = new ArrayList<>();
    private final Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Matrix4f scratch = new Matrix4f();
    private final ByteBuffer savedMvp = MemoryUtil.memAlloc(128);

    private final long queryPool;
    private final boolean[] queried;
    private double msSum;
    private int msFrames, drawCount;

    ShadowPass(FramePlan plan) {
        this.plan = plan;
        this.config = plan.pack.shadow;
        this.id = plan.pack.id;
        colorFormats = new int[plan.shadowTargets.size()];
        colors = new PackRuntime.Image[colorFormats.length];
        for (int i = 0; i < colorFormats.length; i++) {
            int index = plan.shadowTargets.get(i).charAt("shadowcolor".length()) - '0';
            colorFormats[i] = PackRuntime.vkFormat(config.colorFormats[index]);
        }
        mrtBits = Pipeline.registerMrt(colorFormats);
        depthSampler = createDepthSampler(config.hardwareFiltering);
        compareSampler = createDepthSampler(true);
        colorSampler = PackRuntime.createSampler(config.nearest ? VK_FILTER_NEAREST : VK_FILTER_LINEAR, true);
        integerSampler = PackRuntime.createSampler(VK_FILTER_NEAREST, true);
        queried = new boolean[Drawer.framesInFlight()];
        queryPool = FrameProfiler.ENABLED ? createQueryPool(queried.length) : 0L;
    }

    private long createDepthSampler(boolean compare) {
        boolean linear = !config.nearest && (formatFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
        try (MemoryStack stack = stackPush()) {
            int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(filter).minFilter(filter)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .compareEnable(compare).compareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateSampler(Vulkan.getDevice(), info, null, p), "shadow sampler");
            return p.get(0);
        }
    }

    private static int formatFeatures() {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(Vulkan.getDevice().getPhysicalDevice(), FrameTargets.depthFormat(), props);
            return props.optimalTilingFeatures();
        }
    }

    private static long createQueryPool(int frames) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateQueryPool(Vulkan.getDevice(), VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(frames * 2), null, p), "shadow query pool");
            return p.get(0);
        }
    }

    // ------------------------------------------------------------------------------------
    // Frame

    /** Begins the shadow command buffer. The runtime records its uploads, then calls {@link #start}. */
    VkCommandBuffer open() {
        ensureImages();
        cmd = Drawer.beginShadowCommands();
        return cmd;
    }

    /** Starts rendering into the map, cleared. */
    void start(MemoryStack stack) {
        int slot = Drawer.getCurrentFrame() % queried.length;
        if (queryPool != 0L) {
            if (queried[slot]) collect(slot);
            vkCmdResetQueryPool(cmd, queryPool, slot * 2, 2);
            vkCmdWriteTimestamp2(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queryPool, slot * 2);
            queried[slot] = true;
        }
        translucent = depthCopied = sectionsReady = false;
        seen.clear();
        drawCount = 0;

        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1 + colors.length, stack);
        FrameTargets.imageBarrier(b.get(0), depth0.image, FrameTargets.depthAspect(),
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
                        | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        for (int i = 0; i < colors.length; i++) {
            PackRuntime.Image img = colors[i];
            FrameTargets.imageBarrier(b.get(1 + i), img.image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            b.get(1 + i).subresourceRange().levelCount(img.mips);
            img.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            img.stage = VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
            img.access = VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
        }
        FrameTargets.pipelineBarrier(cmd, b, stack);
        beginRendering(true, stack);
        vkCmdSetDepthBias(cmd, 0.0f, 0.0f, 0.0f);
        recording = this;
    }

    private void beginRendering(boolean clear, MemoryStack stack) {
        int load = clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
        VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(depth0.view)
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(load).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        depth.clearValue().depthStencil().depth(1.0f);
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pDepthAttachment(depth);
        if (colors.length > 0) {
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(colors.length, stack);
            for (int i = 0; i < colors.length; i++) {
                VkRenderingAttachmentInfo a = color.get(i).sType$Default().imageView(colors[i].level0View)
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(load).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                // OptiFine clears shadowcolor to white.
                a.clearValue().color().float32(0, 1.0f).float32(1, 1.0f).float32(2, 1.0f).float32(3, 1.0f);
            }
            info.pColorAttachments(color);
        }
        int size = config.resolution;
        info.renderArea().extent().set(size, size);
        vkCmdBeginRendering(cmd, info);
        // Pack space, as everywhere: a positive viewport keeps row 0 at the bottom.
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.x(0.0f).y(0.0f).width(size).height(size).minDepth(0.0f).maxDepth(1.0f);
        vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.extent().set(size, size);
        vkCmdSetScissor(cmd, 0, scissor);
    }

    /** {@code shadowtex1} is the map before the translucent layer. */
    void enterTranslucent(MemoryStack stack) {
        translucent = true;
        if (!plan.shadowDepthCopy || depthCopied) return;
        vkCmdEndRendering(cmd);
        copyDepth(stack);
        beginRendering(false, stack);
    }

    private void copyDepth(MemoryStack stack) {
        depthCopied = true;
        long attachmentStage = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
        long attachmentAccess = VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        long reader = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
                | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        int aspect = FrameTargets.depthAspect();
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(pre.get(0), depth0.image, aspect, attachmentStage, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        FrameTargets.imageBarrier(pre.get(1), depth1.image, aspect, reader, 0,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().set(aspect, 0, 0, 1);
        region.dstSubresource().set(aspect, 0, 0, 1);
        region.extent().set(config.resolution, config.resolution, 1);
        vkCmdCopyImage(cmd, depth0.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                depth1.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(post.get(0), depth0.image, aspect, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, 0,
                attachmentStage, attachmentAccess,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        FrameTargets.imageBarrier(post.get(1), depth1.image, aspect, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT, reader, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    /** The world pass is over: closes the command buffer, the map is ready to sample. */
    void finish(MemoryStack stack) {
        if (recording != this) return;
        recording = null;
        vkCmdEndRendering(cmd);
        if (plan.shadowDepthCopy && !depthCopied) copyDepth(stack);
        long reader = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
                | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1 + colors.length, stack);
        FrameTargets.imageBarrier(b.get(0), depth0.image, FrameTargets.depthAspect(),
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, reader, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        int n = 1;
        for (int i = 0; i < colors.length; i++) {
            PackRuntime.Image img = colors[i];
            if (img.mips > 1) {
                PackRuntime.generateMips(cmd, img, stack);
                continue;
            }
            FrameTargets.imageBarrier(b.get(n++), img.image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    reader, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        FrameTargets.pipelineBarrier(cmd, b.limit(n), stack);
        if (queryPool != 0L) {
            vkCmdWriteTimestamp2(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queryPool,
                    Drawer.getCurrentFrame() % queried.length * 2 + 1);
        }
        Drawer.endShadowCommands();
    }

    private void collect(int slot) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer t = stack.mallocLong(2);
            if (vkGetQueryPoolResults(Vulkan.getDevice(), queryPool, slot * 2, 2, t, 8, VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) return;
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(Vulkan.getDevice().getPhysicalDevice(), props);
            msSum += (t.get(1) - t.get(0)) * props.limits().timestampPeriod() / 1e6;
        }
        if (++msFrames % 120 == 0) {
            Initializer.LOGGER.info(String.format("[gpu] shadow %s: %.3f ms/frame, %d sections, %d draws, %dx%d", id,
                    msSum / 120, sections.size(), drawCount, config.resolution, config.resolution));
            msSum = 0;
        }
    }

    // ------------------------------------------------------------------------------------
    // Drawing

    /** Sends draws to the shadow command buffer until {@link #leave}. */
    public void enter() {
        mainCmd = Drawer.swapCommandBuffer(cmd);
        drawing = this;
        drawCount++;
    }

    public void leave() {
        drawing = null;
        Drawer.swapCommandBuffer(mainCmd);
    }

    /** The pipeline state of shadow draws, whatever the main pass set. */
    long stateKey(int topology) {
        return Pipeline.packStateKey(translucent ? Pipeline.DEFAULT_BLEND_STATE : Pipeline.NO_BLEND_STATE, true, true,
                Pipeline.DepthState.depthFuncToVulkan(515), Pipeline.DEFAULT_LOGICOP_STATE,
                Pipeline.DEFAULT_COLORMASK.colorMask, false, topology, true, false) | mrtBits;
    }

    static Pipeline.Program program(Pipeline pipeline) {
        Pipeline.Program[] table = pipeline.packPrograms;
        return table == null ? null : table[GeometryCategory.SHADOW.ordinal()];
    }

    public boolean drawsLayer(BlockRenderLayer layer) {
        return config.terrain && (config.translucent || layer != BlockRenderLayer.TRANSLUCENT)
                && plan.vertexFlags == ChunkBatcher.vertexFlags();
    }

    /** An entity or block entity draw the map should get. */
    boolean drawsEntity(Pipeline pipeline) {
        return program(pipeline) != null && (EntityRenderer.isTesr() ? config.blockEntities : config.entities && entityInBox);
    }

    public void markSeen(Entity entity) {
        seen.add(entity);
        entityInBox = inBox(entity.posX, entity.posY, entity.posZ, 4.0f);
    }

    /**
     * What the main pass culled still casts a shadow, and so does the first-person player. They
     * run through vanilla's entity code with the main draw skipped.
     */
    public void renderUnseenEntities(Entity viewer, float partialTicks) {
        if (!config.entities) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return;
        EntityRenderer.setRtOnly(true);
        try {
            for (Entity e : mc.world.loadedEntityList) {
                if (seen.contains(e) || e.isDead || !inBox(e.posX, e.posY, e.posZ, 4.0f)) continue;
                // As far as vanilla draws it, also for what the camera does not see.
                if (e != viewer && !e.isInRangeToRender3d(FrameUniforms.cameraX(), FrameUniforms.cameraY(), FrameUniforms.cameraZ())) {
                    continue;
                }
                entityInBox = true;
                if (e == viewer && (mc.gameSettings.thirdPersonView != 0 || (viewer instanceof net.minecraft.entity.player.EntityPlayer p
                        && (p.isPlayerSleeping() || p.isSpectator())))) {
                    continue;
                }
                mc.getRenderManager().renderEntityStatic(e, partialTicks, false);
            }
        } finally {
            EntityRenderer.setRtOnly(false);
            EntityRenderer.setCurrentEntity(null);
        }
    }

    /** True when a sphere around a world point meets the light's box. */
    private boolean inBox(double x, double y, double z, float radius) {
        Matrix4f v = FrameUniforms.shadowView();
        float dx = (float) (x - FrameUniforms.cameraX()), dy = (float) (y - FrameUniforms.cameraY()),
                dz = (float) (z - FrameUniforms.cameraZ());
        float vx = v.m00() * dx + v.m10() * dy + v.m20() * dz + v.m30();
        float vy = v.m01() * dx + v.m11() * dy + v.m21() * dz + v.m31();
        float vz = v.m02() * dx + v.m12() * dy + v.m22() * dz + v.m32();
        float half = config.distance + radius;
        return Math.abs(vx) <= half && Math.abs(vy) <= half && vz <= radius && vz >= -256.0f - radius;
    }

    /** Sections with geometry in the light's box, once per frame. */
    public List<RenderChunk> sections() {
        if (sectionsReady) return sections;
        sectionsReady = true;
        sections.clear();
        net.vulkanmod.mixin.render.RenderGlobalAccessor global =
                (net.vulkanmod.mixin.render.RenderGlobalAccessor) Minecraft.getMinecraft().renderGlobal;
        net.minecraft.client.renderer.ViewFrustum frustum = global.vulkanmod$getViewFrustum();
        if (frustum == null) return sections;
        int builds = BUILDS_PER_FRAME;
        for (RenderChunk chunk : frustum.renderChunks) {
            BlockPos p = chunk.getPosition();
            if (config.culling && !inBox(p.getX() + 8.0, p.getY() + 8.0, p.getZ() + 8.0, 13.9f)) continue;
            // Vanilla builds what the camera sees; the light sees more. Vanilla's own queue is no
            // place for that: what stays in it near the camera is rebuilt on this thread every frame.
            if (builds > 0 && chunk.needsUpdate() && !chunk.needsImmediateUpdate()
                    && global.vulkanmod$getRenderDispatcher().updateChunkLater(chunk)) {
                chunk.clearNeedsUpdate();
                builds--;
            }
            if (!chunk.getCompiledChunk().isEmpty()) sections.add(chunk);
        }
        buildsPending = builds == 0;
        return sections;
    }

    /** Out-of-view sections are still waiting for their build, so the list is worth another walk. */
    public boolean sectionsPending() {
        return buildsPending;
    }

    /** Swaps the entity matrices of {@link VRenderSystem} for the light's. */
    void applyEntityMatrices() {
        ByteBuffer mv = VRenderSystem.modelViewMatrix, mvp = VRenderSystem.MVP;
        MemoryUtil.memCopy(MemoryUtil.memAddress0(mvp), MemoryUtil.memAddress(savedMvp), 64);
        MemoryUtil.memCopy(MemoryUtil.memAddress0(mv), MemoryUtil.memAddress(savedMvp) + 64, 64);
        scratch.set(0, mv);
        FrameUniforms.cameraViewInverse().mul(scratch, scratch);
        FrameUniforms.shadowView().mul(scratch, scratch);
        scratch.get(0, mv);
        FrameUniforms.shadowProjectionRemapped().mul(scratch, scratch);
        scratch.get(0, mvp);
    }

    void restoreEntityMatrices() {
        MemoryUtil.memCopy(MemoryUtil.memAddress(savedMvp), MemoryUtil.memAddress0(VRenderSystem.MVP), 64);
        MemoryUtil.memCopy(MemoryUtil.memAddress(savedMvp) + 64, MemoryUtil.memAddress0(VRenderSystem.modelViewMatrix), 64);
    }

    // ------------------------------------------------------------------------------------
    // Resources

    /** Points a descriptor at a shadow texture. */
    void bind(VkDescriptorImageInfo info, String name) {
        switch (name) {
            case "shadowtex0" -> info.sampler(depthSampler).imageView(depth0.view);
            case "shadowtex1" -> info.sampler(depthSampler).imageView(depth1.view);
            case "shadowtex0Hw" -> info.sampler(compareSampler).imageView(depth0.view);
            case "shadowtex1Hw" -> info.sampler(compareSampler).imageView(depth1.view);
            default -> {
                int at = plan.shadowTargets.indexOf(name);
                // Integer formats cannot be filtered.
                boolean integer = config.colorFormats[IrPack.SHADOW_COLORS.indexOf(name)].isInteger();
                info.sampler(integer ? integerSampler : colorSampler).imageView(colors[at].view);
            }
        }
    }

    void ensureImages() {
        if (allocated) return;
        allocated = true;
        int size = config.resolution;
        depth0 = createDepth(size);
        if (plan.shadowDepthCopy) depth1 = createDepth(size);
        for (int i = 0; i < colors.length; i++) {
            int index = plan.shadowTargets.get(i).charAt("shadowcolor".length()) - '0';
            int mips = config.colorMipmaps[index] ? 32 - Integer.numberOfLeadingZeros(size) : 1;
            colors[i] = PackRuntime.createImage(size, size, mips, colorFormats[i], VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                    | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        }
    }

    private static PackRuntime.Image createDepth(int size) {
        PackRuntime.Image img = new PackRuntime.Image();
        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            MemoryManager.createImage(size, size, 1, FrameTargets.depthFormat(), VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            img.image = pImage.get(0);
            img.memory = pMemory.get(0);
        }
        img.view = Vulkan.createImageView(img.image, FrameTargets.depthFormat(), VK_IMAGE_ASPECT_DEPTH_BIT, 1);
        return img;
    }

    void destroyImages() {
        if (!allocated) return;
        allocated = false;
        VkDevice device = Vulkan.getDevice();
        for (PackRuntime.Image img : new PackRuntime.Image[]{depth0, depth1}) {
            if (img == null) continue;
            vkDestroyImageView(device, img.view, null);
            MemoryManager.freeImage(img.image, img.memory);
        }
        depth0 = depth1 = null;
        for (int i = 0; i < colors.length; i++) {
            if (colors[i].level0View != colors[i].view) vkDestroyImageView(device, colors[i].level0View, null);
            vkDestroyImageView(device, colors[i].view, null);
            MemoryManager.freeImage(colors[i].image, colors[i].memory);
            colors[i] = null;
        }
    }

    void destroy() {
        destroyImages();
        if (recording == this) recording = null;
        VkDevice device = Vulkan.getDevice();
        vkDestroySampler(device, depthSampler, null);
        vkDestroySampler(device, compareSampler, null);
        vkDestroySampler(device, colorSampler, null);
        vkDestroySampler(device, integerSampler, null);
        if (queryPool != 0L) vkDestroyQueryPool(device, queryPool, null);
        MemoryUtil.memFree(savedMvp);
    }
}
