package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
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
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

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
 * {@code glLineWidth} through the line pipeline's dynamic line-width state; depth and
 * fog states are read from the live GL state. The {@code POSITION_COLOR} lines always
 * blend with SRC_ALPHA/ONE_MINUS_SRC_ALPHA so the alpha-0 strip joints fade out instead
 * of painting an opaque web, while the textured beam draws keep the mirrored blend.</p>
 *
 * <p>The dragon's healing beams ({@code RenderDragon.renderCrystalBeams}) are the one
 * world-pass {@code POSITION_TEX_COLOR} strip draw: same replay path, but through the
 * textured pipeline with the beam texture from the tracked bind.</p>
 */
public class DebugLinesRenderer {

    /** POSITION_COLOR with fog, no texture: matches the debug renderers' disableTexture2D. */
    private static Pipeline linePipeline;

    private static final VertexFormat POSITION_COLOR = DefaultVertexFormats.POSITION_COLOR;
    // The dragon's healing beams: a GL_TRIANGLE_STRIP ribbon with a texture and
    // per-vertex colour, drawn through the same shared Tessellator the debug renderers use.
    private static final VertexFormat POSITION_TEX_COLOR = DefaultVertexFormats.POSITION_TEX_COLOR;

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
        VertexFormat format = buffer.getVertexFormat();
        if (linePipeline == null || format == null) {
            return false;
        }
        int mode = buffer.getDrawMode();
        if (format == POSITION_TEX_COLOR) {
            // The dragon's healing beams are a GL_TRIANGLE_STRIP ribbon (begin(5) is
            // GL_TRIANGLE_STRIP, not a line mode) - the only textured strip that reaches
            // this hook; entity quads (mode 7) are captured upstream first.
            return mode == GL11.GL_LINES || mode == GL11.GL_LINE_STRIP
                    || mode == GL11.GL_TRIANGLE_STRIP;
        }
        return mode == GL11.GL_LINES || mode == GL11.GL_LINE_STRIP;
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

        int topology = switch (buffer.getDrawMode()) {
            case GL11.GL_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case GL11.GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            default -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
        };
        ByteBuffer data = buffer.getByteBuffer();

        VertexFormat format = buffer.getVertexFormat();
        boolean textured = format == POSITION_TEX_COLOR;

        try {
            VRenderSystem.updateProjectionMatrix();
            VRenderSystem.updateModelViewMatrix();
            if (!textured) {
                // Rotation-only modelview: the debug renderers' vertices are eye-relative,
                // so the residual translation Kirino reports must not be applied on top.
                // The dragon beams are the exception - RenderDragon.doRender has already
                // translated the modelview to the entity, so their vertices are entity-local
                // and need the full live modelview updateModelViewMatrix just applied.
                Matrix4f mv = new Matrix4f(VRenderSystem.getModelViewMatrix4f());
                mv.set(0, 3, 0.0f).set(1, 3, 0.0f).set(2, 3, 0.0f);
                VRenderSystem.applyModelViewMatrix(mv);
                VRenderSystem.calculateMVP();
            }

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

            if (textured) {
                // Beams run under whatever blend the entity pass left behind, like vanilla.
                Drawer.currentBlendState = VRenderSystem.getBlendState();
            } else {
                // The debug renderers build their LINE_STRIPs with alpha-0 "pen-up" joints
                // between the visible segments (chunk borders connect every chunk corner,
                // the sight lines start at the eye with alpha 0). Vanilla 1.12.2 disables
                // blend around these draws, which turns those joints into the opaque web
                // across the middle of the chunk that real players never accept; the
                // reference look fades them out. SRC_ALPHA/ONE_MINUS_SRC_ALPHA reproduces
                // it: alpha-1 segments stay solid, alpha-0 joints vanish, the red sight
                // lines fade toward the crosshair.
                Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
            }

            VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            if (textured) {
                // Textured strips/lines (dragon beams): the gui_tex_color pipeline already
                // does texture * vertexColor * ColorModulator, which matches the
                // fixed-function chain these draws run under. The beam texture was bound
                // by RenderDragon through GlStateManager, so the tracked mirror resolves it.
                net.vulkanmod.vulkan.texture.VulkanImage tex = GuiRenderer.trackedTexture();
                if (tex == null) {
                    return;
                }
                net.vulkanmod.vulkan.texture.VTextureSelector.bindTexture(tex);
                VRenderSystem.setShaderPipeline(GuiRenderer.getTexColorPipeline());
            } else {
                VRenderSystem.setShaderPipeline(linePipeline);
            }
            VRenderSystem.setTopology(topology);

            Drawer.getInstance().drawLines(data, format, vertexCount);
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
