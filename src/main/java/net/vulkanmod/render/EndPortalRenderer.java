package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Vulkan port of the End portal block entity
 * ({@code TileEntityEndPortalRenderer}). The vanilla TESR draws its stacked planes as
 * {@code POSITION_COLOR} quads and lets the fixed-function pipeline synthesize the
 * texture coordinates from eye-linear texgen planes plus a scrolling texture matrix -
 * state Kirino never emulates, which is why the portal rendered as untextured quads.
 *
 * <p>The port replays each plane draw with the coordinates computed on the CPU: the
 * vertex is taken through the live modelview to eye space (the dispatcher's block
 * translation and the per-pass rotation ride that matrix), the mirrored
 * {@code GL_EYE_PLANE} coefficients produce (s, t, r) exactly as GL_EYE_LINEAR would,
 * and the unit's mirrored texture matrix turns them into the final (u, v). The result
 * is emitted as a {@code POSITION_TEX_COLOR} batch through the gui tex-color pipeline,
 * which already multiplies texture * vertex color * ColorModulator like the
 * fixed-function chain. Blend, depth and cull follow the live GL state, so the sky
 * cube's SRC_ALPHA pass and the portal planes' additive ONE/ONE pass each replay as
 * vanilla configured them.</p>
 */
public class EndPortalRenderer {

    private static final VertexFormat POSITION_COLOR = DefaultVertexFormats.POSITION_COLOR;
    private static final VertexFormat OUTPUT_FORMAT = DefaultVertexFormats.POSITION_TEX_COLOR;

    private static boolean active;
    private static ByteBuffer scratch;

    /** Set around {@code TileEntityEndPortalRenderer.render} by the mixin. */
    public static void setActive(boolean value) {
        active = value;
    }

    /** True while the TESR is drawing and this buffer is one of its plane batches. */
    public static boolean accepts(BufferBuilder buffer) {
        return active
                && buffer.getVertexFormat() == POSITION_COLOR
                && buffer.getDrawMode() == GL11.GL_QUADS
                && buffer.getVertexCount() >= 3;
    }

    /** Replays the finished plane batch with CPU-generated texture coordinates. */
    public static void render(BufferBuilder buffer) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || !Drawer.isActive() || Drawer.skipRendering) {
            return;
        }
        VulkanImage texture = GuiRenderer.trackedTexture();
        if (texture == null) {
            return;
        }

        int vertexCount = buffer.getVertexCount();
        int outStride = OUTPUT_FORMAT.getIntegerSize();
        if (scratch == null || scratch.capacity() < vertexCount * outStride) {
            scratch = MemoryUtil.memAlloc(Math.max(vertexCount * outStride, 1024));
        }

        try {
            VRenderSystem.updateProjectionMatrix();
            VRenderSystem.updateModelViewMatrix();
            Matrix4f mv = new Matrix4f(VRenderSystem.getModelViewMatrix4f());
            Matrix4f texMat = VRenderSystem.getTextureMatrix4f();
            float[] planeS = VRenderSystem.texGenPlaneS;
            float[] planeT = VRenderSystem.texGenPlaneT;
            float[] planeR = VRenderSystem.texGenPlaneR;

            int inStride = POSITION_COLOR.getIntegerSize();
            ByteBuffer src = buffer.getByteBuffer();
            scratch.position(0);
            for (int i = 0; i < vertexCount; i++) {
                int base = i * inStride;
                float x = src.getFloat(base);
                float y = src.getFloat(base + 4);
                float z = src.getFloat(base + 8);
                int color = src.getInt(base + 12);

                // Eye position: the full modelview, so the dispatcher's block-relative
                // translation and the per-pass rotate/scale are applied like in GL.
                float xe = mv.m00() * x + mv.m01() * y + mv.m02() * z + mv.m03();
                float ye = mv.m10() * x + mv.m11() * y + mv.m12() * z + mv.m13();
                float ze = mv.m20() * x + mv.m21() * y + mv.m22() * z + mv.m23();
                float we = mv.m30() * x + mv.m31() * y + mv.m32() * z + mv.m33();

                // GL_EYE_LINEAR: (s, t, r) = plane . (xe, ye, ze, 1).
                float s = planeS[0] * xe + planeS[1] * ye + planeS[2] * ze + planeS[3] * we;
                float t = planeT[0] * xe + planeT[1] * ye + planeT[2] * ze + planeT[3] * we;
                float r = planeR[0] * xe + planeR[1] * ye + planeR[2] * ze + planeR[3] * we;

                // The generated coordinate passes through the unit's texture matrix,
                // which the TESR scrolls per pass (scale 0.5 + translate).
                float u = texMat.m00() * s + texMat.m01() * t + texMat.m02() * r + texMat.m03();
                float v = texMat.m10() * s + texMat.m11() * t + texMat.m12() * r + texMat.m13();

                scratch.putFloat(x).putFloat(y).putFloat(z)
                       .putFloat(u).putFloat(v)
                       .putInt(color);
            }
            scratch.position(0);

            // Depth and cull straight from the live GL state: the TESR runs inside the
            // block-entity pass with the world's depth setup intact.
            if (boolState(GL11.GL_DEPTH_TEST)) {
                VRenderSystem.enableDepthTest();
            } else {
                VRenderSystem.disableDepthTest();
            }
            VRenderSystem.depthMask(boolState(GL11.GL_DEPTH_WRITEMASK));
            if (VRenderSystem.glCull) {
                VRenderSystem.enableCull();
            } else {
                VRenderSystem.disableCull();
            }
            VRenderSystem.setFogState(VRenderSystem.FOG_NONE);
            VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            VRenderSystem.setShaderPipeline(GuiRenderer.getTexColorPipeline());
            Drawer.currentBlendState = VRenderSystem.getBlendState();
            VTextureSelector.bindTexture(texture);

            Drawer.getInstance().draw(scratch, GL11.GL_QUADS, OUTPUT_FORMAT, vertexCount);
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("end portal", t);
        } finally {
            Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
            VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            VRenderSystem.depthMask(true);
            VRenderSystem.enableCull();
            TextureBridge.restoreAtlasBinding();
        }
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

    private EndPortalRenderer() {
    }
}
