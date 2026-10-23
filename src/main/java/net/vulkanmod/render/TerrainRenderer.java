package net.vulkanmod.render;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
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
        if (terrainPipeline == null) {
            return;
        }

        try {
            VRenderSystem.setShaderPipeline(terrainPipeline);
            snapshotState();
            TextureBridge.updateTextures();
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

        // Blend
        if (boolState(GL11.GL_BLEND)) {
            VRenderSystem.enableBlend();
            GL11.glGetIntegerv(GL14.GL_BLEND_SRC_RGB, int1);
            int srcRgb = int1.get(0);
            GL11.glGetIntegerv(GL14.GL_BLEND_DST_RGB, int1);
            int dstRgb = int1.get(0);
            GL11.glGetIntegerv(GL14.GL_BLEND_SRC_ALPHA, int1);
            int srcAlpha = int1.get(0);
            GL11.glGetIntegerv(GL14.GL_BLEND_DST_ALPHA, int1);
            int dstAlpha = int1.get(0);
            VRenderSystem.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        } else {
            VRenderSystem.disableBlend();
        }

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
