package net.vulkanmod.render;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Owns the terrain pipeline and mirrors the fixed-function OpenGL state that the
 * 1.12.2 chunk renderer relies on (projection, fog, blend, depth, color) into
 * {@link VRenderSystem} before each chunk layer pass.
 *
 * <p>The state is read straight from the GL context instead of shadowing the
 * {@code GlStateManager} calls: on 1.12.2 the GL pipeline still executes fully (only
 * the swap is replaced), so GL is the single source of truth and cannot drift.</p>
 */
public class TerrainRenderer {

    private static Pipeline terrainPipeline;

    private static final FloatBuffer float4 = MemoryUtil.memAllocFloat(4);
    private static final FloatBuffer float1 = MemoryUtil.memAllocFloat(1);
    private static final IntBuffer int1 = MemoryUtil.memAllocInt(1);
    private static final ByteBuffer bool1 = MemoryUtil.memAlloc(1);

    /** The layer of the chunk pass currently being recorded (null outside world rendering). */
    public static BlockRenderLayer currentLayer;

    /** Debug knob: VULKANMOD_NO_TRANSLUCENT=1 skips the translucent (water) layer. */
    public static final boolean NO_TRANSLUCENT = System.getenv("VULKANMOD_NO_TRANSLUCENT") != null;

    /**
     * Chunk sections render through the batched path (shared vertex arena + per-section
     * matrix SSBO + one indirect draw per layer), see {@link ChunkBatcher}. Default since B8:
     * camera history and ray tracing build on the persistent arena. VULKANMOD_BATCH=0 falls
     * back to one draw call per section.
     */
    public static final boolean BATCH = isEnvOn("VULKANMOD_BATCH", true);

    /** Env knob parsing: unset gives the default; a value starting with "0" switches it off. */
    private static boolean isEnvOn(String name, boolean def) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) return def;
        return v.charAt(0) != '0';
    }

    /** Diagnostic: VULKANMOD_NO_TERRAIN=1 skips the whole chunk pass (to bound terrain cost). */
    public static final boolean NO_TERRAIN = System.getenv("VULKANMOD_NO_TERRAIN") != null;

    /** Throwaway diagnostic: VULKANMOD_NOQUERY=1 skips the per-chunk matrix query (cost probe). */
    public static final boolean NOQUERY = System.getenv("VULKANMOD_NOQUERY") != null;

    /**
     * The fixed-function GL state mirrored by {@link #snapshotFixedState()} (fog, depth, color
     * mask, cull) is set up once before the world render and is identical for all four chunk
     * layers, so it is queried only on the first layer of each frame. The Kirino GL {@code glGet*}
     * queries are slow enough that re-reading them per layer dominated the chunk pass (~2 ms).
     * Reset from {@link Initializer#beginFrame()}.
     */
    private static boolean fixedStateCached;

    /** Clears the per-frame fixed-state cache; called once per frame from {@code Initializer.beginFrame}. */
    public static void onFrameBegin() {
        fixedStateCached = false;
        ++frameCounter;
        // Arm the matrix dump for exactly the frame after armMatrixDump() was called
        // (onFrameBegin runs before the frame renders, so latch through a pending flag).
        dumpFramesLeft = dumpPending ? 1 : 0;
        dumpPending = false;
    }

    private static long frameCounter;

    // Batched per-section matrix derivation: camera matrices captured at layer start, and the
    // section origin + camera position stashed by ChunkRenderContainerM.preRenderChunk.
    private static final org.joml.Matrix4f batchCameraMV = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f batchProjRemap = new org.joml.Matrix4f();
    private static long lastCaptureFrame = -1;
    private static double batchCamX, batchCamY, batchCamZ;
    private static int pendingX, pendingY, pendingZ;

    /** Records the current section's origin and the camera position (called from preRenderChunk). */
    public static void stashChunkTransform(int x, int y, int z, double camX, double camY, double camZ) {
        pendingX = x;
        pendingY = y;
        pendingZ = z;
        batchCamX = camX;
        batchCamY = camY;
        batchCamZ = camZ;
    }

    /** Builds the current section's model-view/MVP from the stashed origin (camera-relative). */
    public static void applyCurrentSectionMatrix() {
        VRenderSystem.applySectionModelView(batchCameraMV, batchProjRemap,
                (float) (pendingX - batchCamX),
                (float) (pendingY - batchCamY),
                (float) (pendingZ - batchCamZ));
    }

    /**
     * Diagnostic knob: VULKANMOD_DUMP_MVP=1 dumps every section's slot, origin, camera and the
     * exact MVP matrix written to the SSBO for one frame (armed by the autotest at capture
     * time) to {@code vm_mvp_dump.txt}, so section-boundary seams can be checked offline.
     */
    public static final boolean DUMP_MVP = System.getenv("VULKANMOD_DUMP_MVP") != null;
    public static volatile int dumpFramesLeft;
    private static volatile boolean dumpPending;
    private static java.io.PrintWriter dumpOut;

    /** Requests the matrix dump for the next rendered frame. */
    public static void armMatrixDump() {
        dumpPending = true;
    }

    /** Called from the batched draw path once per recorded section. */
    public static void dumpSectionMatrix(int slot) {
        try {
            if (dumpOut == null) {
                java.io.File f = new java.io.File(
                        net.minecraft.client.Minecraft.getMinecraft().gameDir, "vm_mvp_dump.txt");
                dumpOut = new java.io.PrintWriter(new java.io.FileWriter(f));
            }
            java.nio.ByteBuffer mvp = VRenderSystem.MVP;
            StringBuilder sb = new StringBuilder(256);
            sb.append(slot).append(' ').append(pendingX).append(' ').append(pendingY).append(' ')
              .append(pendingZ).append(' ').append(batchCamX).append(' ').append(batchCamY)
              .append(' ').append(batchCamZ).append(' ').append(currentLayer);
            // Live GL state at record time: tells whether the GL projection/modelview drift
            // between layers (P11 = projection m11, M5 = modelview m11, should be constant).
            float[] glP = new float[16];
            float[] glM = new float[16];
            org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, glP);
            org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, glM);
            sb.append(" glP=").append(glP[5]).append(" glM=").append(glM[5])
              .append(" cam=").append(batchProjRemap.m11());
            for (int k = 0; k < 16; ++k) {
                sb.append(' ').append(Float.toString(mvp.getFloat(k * 4)));
            }
            dumpOut.println(sb);
            dumpOut.flush();
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[terrain] matrix dump failed", t);
        }
    }

    /** Debug knob: VULKANMOD_LOG_BLEND=1 logs the mirrored blend state per chunk layer. */
    private static final boolean LOG_BLEND = System.getenv("VULKANMOD_LOG_BLEND") != null;
    private static BlockRenderLayer lastLoggedLayer;

    /**
     * Debug knob: VULKANMOD_LOG_WATER=1 logs, once a second, how many chunk buffers each
     * render layer actually submitted and how many vertices that was, plus every case where
     * a layer buffer went from non-empty to empty. Distinguishes "the water geometry is
     * gone" from "it is drawn but invisible".
     */
    private static final boolean LOG_STATS = System.getenv("VULKANMOD_LOG_WATER") != null;

    /** Debug knob: VULKANMOD_LOG_UPLOADS=1 logs every chunk-section buffer upload. */
    public static final boolean LOG_UPLOADS = System.getenv("VULKANMOD_LOG_UPLOADS") != null;
    private static final java.util.EnumMap<BlockRenderLayer, int[]> layerStats =
            new java.util.EnumMap<>(BlockRenderLayer.class);
    private static long lastStatsLog;

    /** Records one submitted chunk buffer; called from {@link VBO#draw()}. */
    public static void countDraw(BlockRenderLayer layer, int vertexCount) {
        if (!LOG_STATS || layer == null) {
            return;
        }
        int[] s = layerStats.computeIfAbsent(layer, k -> new int[2]);
        s[0]++;
        s[1] += vertexCount;
    }

    /** Records one chunk-layer buffer upload; called from {@link VBO#upload}. */
    public static void countUpload(BlockRenderLayer layer, int oldCount, int newCount) {
        if (!LOG_STATS || oldCount <= 0 || newCount > 0) {
            return;
        }
        Initializer.LOGGER.info("[terrain] layer buffer EMPTIED ({} -> 0 verts) layer={} (current={})",
                oldCount, layer, currentLayer);
    }

    /**
     * Diagnostic for the "floating plant" bug: within a single chunk-layer pass every chunk is
     * drawn once with its own modelview, so two draws can never share the same modelview
     * translation. A repeated translation within one pass means the modelview query returned a
     * stale value (Kirino GL) and that buffer is being drawn at another chunk's position.
     */
    private static final java.util.EnumMap<BlockRenderLayer, java.util.HashSet<Long>> mvKeys =
            new java.util.EnumMap<>(BlockRenderLayer.class);
    private static long lastStaleLog;

    /** Clears the per-pass modelview set; called from {@link #onChunkLayerStart}. */
    public static void beginLayer(BlockRenderLayer layer) {
        if (LOG_STATS) {
            mvKeys.computeIfAbsent(layer, k -> new java.util.HashSet<>()).clear();
        }
    }

    /** Called from {@link VBO#draw} right after the modelview is read. */
    public static void checkModelviewUnique(BlockRenderLayer layer, int vertexCount) {
        if (!LOG_STATS || layer == null) {
            return;
        }
        java.nio.FloatBuffer fb = VRenderSystem.modelViewMatrix.asFloatBuffer().position(0).limit(16);
        long key = pack(fb.get(12)) * 1000003L + pack(fb.get(13)) * 8191L + pack(fb.get(14));
        if (!mvKeys.computeIfAbsent(layer, k -> new java.util.HashSet<>()).add(key)) {
            long now = System.currentTimeMillis();
            if (now - lastStaleLog > 150L) {
                lastStaleLog = now;
                Initializer.LOGGER.info(
                        "[terrain] STALE MODELVIEW dup layer={} verts={} tx={} ty={} tz={}",
                        layer, vertexCount, fb.get(12), fb.get(13), fb.get(14));
            }
        }
    }

    private static long pack(float f) {
        return (long) (f * 64.0); // ~0.016 resolution; distinct chunks differ by >= 16
    }

    private static long lastRangeLog;

    /**
     * Diagnostic: chunk vertices are baked in coordinates local to the chunk (block position
     * minus the chunk origin), so every position must lie in [0, 16]. A vertex outside that
     * range means the uploaded mesh is corrupt and the block would render displaced. Scans the
     * position attribute (three floats at offset 0, stride {@code stride}) of one upload.
     */
    public static void checkVertexRange(java.nio.ByteBuffer data, int vertexCount, int stride) {
        // Only chunk meshes use the BLOCK format (stride 28); the sky/star VBOs share this
        // upload path with a position-only format and legitimately span +/-384.
        if (!LOG_STATS || data == null || vertexCount <= 0 || stride != 28) {
            return;
        }
        java.nio.FloatBuffer fb = data.duplicate().position(0)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        int badAxis = -1;
        for (int v = 0; v < vertexCount; v++) {
            int base = v * (stride / 4);
            for (int a = 0; a < 3; a++) {
                float p = fb.get(base + a);
                if (p < min) { min = p; }
                if (p > max) { max = p; }
                if ((p < -0.5f || p > 16.5f) && badAxis < 0) {
                    badAxis = a;
                }
            }
        }
        if (badAxis >= 0) {
            long now = System.currentTimeMillis();
            if (now - lastRangeLog > 150L) {
                lastRangeLog = now;
                Initializer.LOGGER.info(
                        "[terrain] OUT-OF-RANGE vertex layer={} verts={} axis={} min={} max={}",
                        currentLayer, vertexCount, badAxis, min, max);
            }
        }
    }

    private static void logStats() {
        if (!LOG_STATS) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStatsLog < 1000L) {
            return;
        }
        lastStatsLog = now;
        StringBuilder sb = new StringBuilder("[terrain] draws/s:");
        for (BlockRenderLayer l : BlockRenderLayer.values()) {
            int[] s = layerStats.remove(l);
            sb.append(' ').append(l).append('=')
                    .append(s == null ? "0d/0v" : s[0] + "d/" + s[1] + "v");
        }
        Initializer.LOGGER.info(sb.toString());
    }

    private static long lastSolidStateLog, lastTranslucentStateLog;

    /**
     * Logs the state that multiplies/discards the fragment for the two layers that matter:
     * Solid (renders correctly, the reference) and Translucent (the disappearing water). A
     * layer that submits geometry but renders black or invisible shows up here.
     */
    private static void logLayerState(BlockRenderLayer layer) {
        if (!LOG_STATS) {
            return;
        }
        long now = System.currentTimeMillis();
        if (layer == BlockRenderLayer.SOLID) {
            if (now - lastSolidStateLog < 1000L) {
                return;
            }
            lastSolidStateLog = now;
        } else if (layer == BlockRenderLayer.TRANSLUCENT) {
            if (now - lastTranslucentStateLog < 1000L) {
                return;
            }
            lastTranslucentStateLog = now;
        } else {
            return;
        }
        float[] cm = VRenderSystem.getShaderColor();
        Initializer.LOGGER.info(
                "[terrain] state({}) colorMod=({},{},{},{}) colorMask={} depthFun={} depthMask={} fog={} blend={} src={} dst={} tex={} light={}",
                layer,
                String.format("%.2f", cm[0]), String.format("%.2f", cm[1]),
                String.format("%.2f", cm[2]), String.format("%.2f", cm[3]),
                VRenderSystem.colorMask, VRenderSystem.depthFun,
                VRenderSystem.depthMask, VRenderSystem.isFogEnabled(), VRenderSystem.blendEnabled,
                VRenderSystem.blendSrcRgb, VRenderSystem.blendDstRgb,
                System.identityHashCode(net.vulkanmod.vulkan.texture.VTextureSelector.getBoundTexture()),
                System.identityHashCode(net.vulkanmod.vulkan.texture.VTextureSelector.getLightTexture()));
    }

    public static void init() {
        // VULKANMOD_SHADER allows swapping in diagnostic shader variants
        String shader = System.getenv().getOrDefault("VULKANMOD_SHADER", "terrain");
        Initializer.LOGGER.info("[terrain] shader variant: {}", shader);
        terrainPipeline = new Pipeline(DefaultVertexFormats.BLOCK, shader);
        VRenderSystem.setShaderPipeline(terrainPipeline);

        if (BATCH) {
            // The batched pipeline reads per-section matrices from an SSBO indexed by a
            // per-vertex slot attribute; it shares the fragment shader and binding layout with
            // the plain terrain pipeline, but uses the BLOCK+slot vertex format.
            // The same VULKANMOD_SHADER knob selects batched variants as "terrain_batch_<name>".
            String batchShader = shader.equals("terrain") ? "terrain_batch" : "terrain_batch_" + shader;
            Pipeline batched = new Pipeline(ChunkBatcher.FORMAT, batchShader);
            int freeDelay = net.vulkanmod.vulkan.Vulkan.getSwapChainImages().size() + 1;
            ChunkArena arena = new ChunkArena(128 * 1024 * 1024, freeDelay, ChunkBatcher.STRIDE);
            ChunkBatcher.init(arena, batched);
        }
    }

    /** Called at the start of every chunk layer pass (see VboRenderListM). */
    public static void onChunkLayerStart(BlockRenderLayer layer) {
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            // F2: no-op when the particle pass already took the copy this frame.
            net.vulkanmod.vulkan.Drawer.snapshotOpaque();
        }
        currentLayer = layer;
        logStats();
        beginLayer(layer);
        if (BATCH) {
            ChunkBatcher.beginLayer();
        }
        if (terrainPipeline == null) {
            return;
        }

        try {
            VRenderSystem.setShaderPipeline(terrainPipeline);
            snapshotState();
            net.vulkanmod.debug.FrameProfiler.texBegin();
            TextureBridge.updateTextures();
            net.vulkanmod.debug.FrameProfiler.texEnd();
            if (BATCH) {
                // Capture the camera model-view + projection once per frame, at the first
                // chunk layer pass. The stack is clean there (setupCameraTransform just
                // loadIdentity'd and rotated it), matching what vanilla's per-chunk draws
                // see for every layer. Do NOT re-capture per layer: Kirino's multMatrix
                // nudged the modelview by ~(1+2^-20) per chunk multiply before the batched
                // path started no-oping multModelviewMatrix (see VboRenderListM, L4); the
                // once-per-frame capture is kept regardless, as re-querying per layer
                // would bake any future drift into the later layers and open sub-pixel
                // cracks where two render layers of the same section meet
                // (e.g. solid dirt vs cutout_mipped grass sides).
                if (lastCaptureFrame != frameCounter) {
                    VRenderSystem.captureCameraMatrices(batchCameraMV, batchProjRemap);
                    lastCaptureFrame = frameCounter;
                    if (DUMP_MVP && dumpFramesLeft > 0) {
                        float[] mv = new float[16];
                        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, mv);
                        Initializer.LOGGER.info("[dump] frame={} layer={} P00={} P11={} glM11={}",
                                frameCounter, layer, batchProjRemap.m00(), batchProjRemap.m11(), mv[5]);
                    }
                }
            }
            logLayerState(layer);
            if (LOG_BLEND && layer != lastLoggedLayer) {
                lastLoggedLayer = layer;
                Initializer.LOGGER.info("[terrain] blend({}) enabled={} srcRgb={} dstRgb={} (GL: 770=SRC_ALPHA 771=ONE_MINUS_SRC_ALPHA 1=ONE 0=ZERO)",
                        layer, VRenderSystem.blendEnabled, VRenderSystem.blendSrcRgb, VRenderSystem.blendDstRgb);
            }
        } catch (Throwable t) {
            reportOnce("chunk layer start", t);
        }
    }

    /** Called at the end of every chunk layer pass; submits the batched layer as one draw. */
    public static void onChunkLayerEnd(BlockRenderLayer layer) {
        if (BATCH) {
            try {
                net.vulkanmod.debug.FrameProfiler.submitBegin();
                ChunkBatcher.endLayer();
                net.vulkanmod.debug.FrameProfiler.submitEnd();
            } catch (Throwable t) {
                reportOnce("chunk layer end", t);
            }
        }
        // Replay the deferred weather now that the translucent (water) layer has been drawn,
        // so falling snow/rain lands on top of the water instead of under it. This runs for
        // every frame (renderChunkLayer is always invoked for the translucent layer).
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            EntityRenderer.flushWeather();
        }
    }

    public static void logState(String what) {
        Initializer.LOGGER.info("[terrain] state({}) fog={}{} proj={} colorModulator={}", what,
                java.util.Arrays.toString(VRenderSystem.getShaderFogColor()),
                java.util.Arrays.toString(VRenderSystem.getShaderColor()),
                floats(VRenderSystem.getProjectionMatrix()), VRenderSystem.getFogState());
    }

    private static String floats(java.nio.ByteBuffer buffer) {
        float[] values = new float[16];
        buffer.asFloatBuffer().position(0).limit(16).get(values);
        return java.util.Arrays.toString(values);
    }

    private static int errors;

    static void reportOnce(String what, Throwable t) {
        if (++errors <= 5) {
            Initializer.LOGGER.error("Terrain error ({}), attempt {}", what, errors, t);
        }
    }

    private static void snapshotState() {
        // Projection matrix; the modelview changes per chunk and is read at draw time.
        VRenderSystem.updateProjectionMatrix();

        // glColor -> ColorModulator (multiplied with the vertex color in the shader). Queried per
        // layer (cheap, one query) in case a layer tints differently.
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, float4);
        VRenderSystem.setShaderColor(float4.get(0), float4.get(1), float4.get(2), float4.get(3));

        snapshotFixedState();

        // Blend. The GL_BLEND / GL_BLEND_SRC_* queries are dead under Kirino GL (they
        // report the GL defaults no matter the real state), so the blend cannot be
        // mirrored by querying here. Instead it is tracked from the vanilla
        // GlStateManager.enableBlend/tryBlendFuncSeparate calls by GlStateManagerBlendM,
        // which mirrors the real GL state one-for-one. Vanilla guarantees the correct
        // per-layer blend before renderChunkLayer runs: disabled for solid/cutout, and
        // SRC_ALPHA/ONE_MINUS_SRC_ALPHA for the translucent (water) layer.
        //
        // Drawer picks the pipeline permutation from this static, not from VRenderSystem,
        // so terrain must publish it here; otherwise it inherits whatever the previous
        // pass (e.g. the HUD vignette's ZERO/ONE_MINUS_SRC_COLOR) left behind, which made
        // water tint purple and flicker.
        Drawer.currentBlendState = VRenderSystem.getBlendState();
    }

    /**
     * Mirrors the fog state, the only fixed-function state that is constant for every
     * chunk layer within a frame. The Kirino {@code glGet*} queries here are slow, so
     * this runs only on the first layer of each frame (see {@link #fixedStateCached}).
     *
     * <p>Depth, color mask and cull are deliberately not queried here: a once-per-frame
     * snapshot handed the later layers the first layer's state (the translucent layer
     * wrote depth like the solid one). They are tracked from the vanilla
     * {@code GlStateManager} calls by {@code GlStateManagerDepthM}/{@code GlStateManagerBlendM}
     * instead, so every layer draws with the state vanilla set for it.</p>
     */
    private static void snapshotFixedState() {
        if (fixedStateCached) {
            return;
        }
        fixedStateCached = true;

        // Safety net (N1): the sky/clouds/GUI passes restore the live depth/cull state
        // on exit, but a pass that leaks an override ("depth off, func ALWAYS") would
        // otherwise reach the terrain, whose vanilla code issues no GlStateManager call
        // between the sky and the SOLID layer. Re-sync from the GL-truth mirror, which
        // only the GlStateManager hooks write.
        VRenderSystem.depthTest = VRenderSystem.glDepthTest;
        VRenderSystem.depthMask = VRenderSystem.glDepthMask;
        VRenderSystem.depthFun = VRenderSystem.glDepthFun;
        VRenderSystem.cull = VRenderSystem.glCull;

        // Fog (fixed-function GL_FOG on 1.12.2)
        if (!boolState(GL11.GL_FOG)) {
            VRenderSystem.setFogState(VRenderSystem.FOG_NONE);
        } else {
            GL11.glGetIntegerv(GL11.GL_FOG_MODE, int1);
            int mode = switch (int1.get(0)) {
                case GL11.GL_EXP -> VRenderSystem.FOG_EXP;
                case GL11.GL_EXP2 -> VRenderSystem.FOG_EXP2;
                default -> VRenderSystem.FOG_LINEAR;
            };
            VRenderSystem.setFogState(mode);

            GL11.glGetFloatv(GL11.GL_FOG_COLOR, float4);
            VRenderSystem.setFogShaderColor(float4.get(0), float4.get(1), float4.get(2), float4.get(3));

            GL11.glGetFloatv(GL11.GL_FOG_START, float1);
            VRenderSystem.setFogStart(float1.get(0));
            GL11.glGetFloatv(GL11.GL_FOG_END, float1);
            VRenderSystem.setFogEnd(float1.get(0));
            GL11.glGetFloatv(GL11.GL_FOG_DENSITY, float1);
            VRenderSystem.setFogDensity(float1.get(0));
        }
    }

    private static boolean boolState(int pname) {
        GL11.glGetBooleanv(pname, bool1);
        return bool1.get(0) != 0;
    }

    private TerrainRenderer() {
    }
}
