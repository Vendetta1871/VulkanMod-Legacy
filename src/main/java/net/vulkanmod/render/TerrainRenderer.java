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
    private static final ByteBuffer bool4 = MemoryUtil.memAlloc(4);

    /** The layer of the chunk pass currently being recorded (null outside world rendering). */
    public static BlockRenderLayer currentLayer;

    /** Debug knob: VULKANMOD_NO_TRANSLUCENT=1 skips the translucent (water) layer. */
    public static final boolean NO_TRANSLUCENT = System.getenv("VULKANMOD_NO_TRANSLUCENT") != null;

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
    }

    /** Called at the start of every chunk layer pass (see VboRenderListM). */
    public static void onChunkLayerStart(BlockRenderLayer layer) {
        currentLayer = layer;
        logStats();
        if (terrainPipeline == null) {
            return;
        }

        try {
            VRenderSystem.setShaderPipeline(terrainPipeline);
            snapshotState();
            TextureBridge.updateTextures();
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

        // glColor -> ColorModulator (multiplied with the vertex color in the shader)
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, float4);
        VRenderSystem.setShaderColor(float4.get(0), float4.get(1), float4.get(2), float4.get(3));

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

        // Depth
        if (boolState(GL11.GL_DEPTH_TEST)) {
            VRenderSystem.enableDepthTest();
        } else {
            VRenderSystem.disableDepthTest();
        }
        VRenderSystem.depthMask(boolState(GL11.GL_DEPTH_WRITEMASK));
        GL11.glGetIntegerv(GL11.GL_DEPTH_FUNC, int1);
        VRenderSystem.depthFunc(int1.get(0));

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

        // Color mask
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, bool4);
        VRenderSystem.colorMask(bool4.get(0) != 0, bool4.get(1) != 0, bool4.get(2) != 0, bool4.get(3) != 0);

        // Cull face
        if (boolState(GL11.GL_CULL_FACE)) {
            VRenderSystem.enableCull();
        } else {
            VRenderSystem.disableCull();
        }
    }

    private static boolean boolState(int pname) {
        GL11.glGetBooleanv(pname, bool1);
        return bool1.get(0) != 0;
    }

    private TerrainRenderer() {
    }
}
