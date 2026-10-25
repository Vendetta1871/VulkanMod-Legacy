package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

/**
 * Vulkan port of the world-space debug geometry that 1.12.2 draws through the shared
 * {@code Tessellator} with {@code POSITION_COLOR} lines: the F3 debug renderers
 * (chunk borders, collision boxes, water, pathfinding, neighbours-update, solid faces)
 * and the block selection box. All of them funnel through
 * {@code WorldVertexBufferUploader.draw}, whose GL draw is a {@code glDrawArrays} that
 * Kirino never presents, so the port captures the buffer there and replays it here.
 *
 * <p>The renderers build their vertices eye-relative (they subtract the interpolated
 * camera position), so the walls are drawn with a rotation-only modelview, exactly like
 * the sky/cloud/border passes. The line width comes from the mirrored
 * {@code glLineWidth} through the line pipeline's dynamic line-width state; the blend,
 * depth and fog states are read from the live GL state so each renderer's own sequence
 * (chunk borders draw unblended, the selection box blends) is reproduced per draw.</p>
 */
public class DebugLinesRenderer {

    /** POSITION_COLOR with fog, no texture: matches the debug renderers' disableTexture2D. */
    private static Pipeline linePipeline;

    private static final VertexFormat POSITION_COLOR = DefaultVertexFormats.POSITION_COLOR;

    private static final FloatBuffer float4 = MemoryUtil.memAllocFloat(4);
    private static final FloatBuffer float1 = MemoryUtil.memAllocFloat(1);

    public static void init() {
        linePipeline = new Pipeline(POSITION_COLOR, "debug_lines");
    }

    /**
     * True when this buffer is debug/selection line geometry the port can replay. Called
     * from the {@code WorldVertexBufferUploader} hook before {@link #render}; the entity
     * and GUI passes capture their own batches first, so this only fires for the world-pass
     * line draws.
     */
    public static boolean accepts(BufferBuilder buffer) {
        return linePipeline != null
                && buffer.getVertexFormat() == POSITION_COLOR
                && (buffer.getDrawMode() == GL11.GL_LINES || buffer.getDrawMode() == GL11.GL_LINE_STRIP);
    }

    /** Replays the finished buffer as one line batch. The buffer is already position(0). */
    public static void render(BufferBuilder buffer) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || !Drawer.isActive() || Drawer.skipRendering) {
            return;
        }

        int vertexCount = buffer.getVertexCount();
        if (vertexCount < 2) {
            return;
        }

        int topology = buffer.getDrawMode() == GL11.GL_LINE_STRIP ? VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
                : VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
        ByteBuffer data = buffer.getByteBuffer();

        try {
            VRenderSystem.updateProjectionMatrix();
            VRenderSystem.updateModelViewMatrix();
            // Rotation-only modelview: the vertices are eye-relative, so the residual
            // translation Kirino reports must not be applied on top.
            Matrix4f mv = new Matrix4f(VRenderSystem.getModelViewMatrix4f());
            mv.set(0, 3, 0.0f).set(1, 3, 0.0f).set(2, 3, 0.0f);
            VRenderSystem.applyModelViewMatrix(mv);
            VRenderSystem.calculateMVP();

            snapshotFog();

            // Depth state straight from the live GL: the selection box draws with
            // depthMask(false), the debug renderers with the world default.
            if (boolState(GL11.GL_DEPTH_TEST)) {
                VRenderSystem.enableDepthTest();
            } else {
                VRenderSystem.disableDepthTest();
            }
            GL11.glGetIntegerv(GL11.GL_DEPTH_FUNC, int1());
            VRenderSystem.depthFunc(int1().get(0));
            VRenderSystem.depthMask(boolState(GL11.GL_DEPTH_WRITEMASK));

            // Blend is mirrored into VRenderSystem by the GlStateManager hook; the chunk
            // borders run with it disabled, the selection box with SRC_ALPHA/ONE_MINUS.
            Drawer.currentBlendState = VRenderSystem.getBlendState();

            VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            VRenderSystem.setShaderPipeline(linePipeline);
            VRenderSystem.setTopology(topology);

            Drawer.getInstance().drawLines(data, POSITION_COLOR, vertexCount);
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("debug lines", t);
        } finally {
            VRenderSystem.setTopology(TRIANGLE_LIST);
            Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
            VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            VRenderSystem.depthMask(true);
            VRenderSystem.enableCull();
            TextureBridge.restoreAtlasBinding();
        }
    }

    private static final int TRIANGLE_LIST = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    // --- state helpers (mirroring WorldBorderRenderer; kept local so the renderers stay
    //     self-contained the way TerrainRenderer, SkyRenderer, CloudRenderer each are) ---

    private static void snapshotFog() {
        if (!boolState(GL11.GL_FOG)) {
            VRenderSystem.setFogState(VRenderSystem.FOG_NONE);
            return;
        }
        java.nio.IntBuffer int1 = int1();
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

    private static java.nio.IntBuffer int1Buffer;

    private static java.nio.IntBuffer int1() {
        if (int1Buffer == null) {
            int1Buffer = MemoryUtil.memAllocInt(1);
        }
        return int1Buffer;
    }

    private static java.nio.ByteBuffer bool1Buffer;

    private static boolean boolState(int pname) {
        java.nio.ByteBuffer bool1 = bool1Buffer;
        if (bool1 == null) {
            bool1 = bool1Buffer = MemoryUtil.memAlloc(1);
        }
        GL11.glGetBooleanv(pname, bool1);
        return bool1.get(0) != 0;
    }

    private DebugLinesRenderer() {
    }
}
