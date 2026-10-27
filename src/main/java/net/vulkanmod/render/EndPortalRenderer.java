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
 * vanilla geometry is the block's own faces under the plain dispatcher modelview, the
 * eye-linear planes turn the eye position into (s, t, r), and the unit's texture matrix -
 * the mirrored scroll/rotate/scale chain times the projection and modelview the TESR
 * bakes into it with {@code multMatrix} - maps them to the screen-projected (u, v) that
 * gives the portal its window-into-space look. The result is emitted as a
 * {@code POSITION_TEX_COLOR} batch through the gui tex-color pipeline, which already
 * multiplies texture * vertex color * ColorModulator like the fixed-function chain.
 * Blend, depth and cull follow the live GL state, so the sky plane's SRC_ALPHA pass and
 * the portal planes' additive ONE/ONE pass each replay as vanilla configured them.</p>
 */
public class EndPortalRenderer {

    private static final VertexFormat POSITION_COLOR = DefaultVertexFormats.POSITION_COLOR;
    private static final VertexFormat OUTPUT_FORMAT = DefaultVertexFormats.POSITION_TEX_COLOR;

    private static boolean active;
    private static ByteBuffer scratch;
    private static final Matrix4f SCRATCH_P = new Matrix4f();
    private static final Matrix4f SCRATCH_MV = new Matrix4f();

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
        // Byte strides: getIntegerSize() counts 4-byte UNITS, but the ByteBuffer offsets
        // below (and the scratch capacity) are in BYTES - use getSize() for both.
        int outStride = OUTPUT_FORMAT.getSize();
        if (scratch == null || scratch.capacity() < vertexCount * outStride) {
            scratch = MemoryUtil.memAlloc(Math.max(vertexCount * outStride, 1024));
        }

        try {
            VRenderSystem.updateProjectionMatrix();
            VRenderSystem.updateModelViewMatrix();
            Matrix4f mv = new Matrix4f(VRenderSystem.getModelViewMatrix4f());
            // The TESR runs its whole texture-coordinate chain (scroll translate, rotate,
            // scale) in matrixMode GL_TEXTURE and then bakes the projection and modelview
            // it captured at entry into the texture matrix with two multMatrix calls - the
            // screen-space projection that makes the portal look like a window into space.
            // The mirror tracks the chain but not multMatrix, so fold the same bake in.
            Matrix4f texMat = VRenderSystem.getTextureMatrix4f();
            texMat.mul(SCRATCH_P.set(VRenderSystem.getProjectionMatrix4f()))
                  .mul(SCRATCH_MV.set(VRenderSystem.getModelViewMatrix4f()));
            float[] planeS = VRenderSystem.texGenPlaneS;
            float[] planeT = VRenderSystem.texGenPlaneT;
            float[] planeR = VRenderSystem.texGenPlaneR;

            int inStride = POSITION_COLOR.getSize();
            ByteBuffer src = buffer.getByteBuffer();
            scratch.position(0);
            for (int i = 0; i < vertexCount; i++) {
                int base = i * inStride;
                float x = src.getFloat(base);
                float y = src.getFloat(base + 4);
                float z = src.getFloat(base + 8);
                int color = src.getInt(base + 12);

                // Eye position through the live modelview (joml indexes m<col><row>, so a
                // row of the matrix is m00, m10, m20, m30). The dispatcher's block-relative
                // translation rides this matrix; the per-pass transforms do not - they live
                // in the texture matrix, exactly like in GL.
                float xe = mv.m00() * x + mv.m10() * y + mv.m20() * z + mv.m30();
                float ye = mv.m01() * x + mv.m11() * y + mv.m21() * z + mv.m31();
                float ze = mv.m02() * x + mv.m12() * y + mv.m22() * z + mv.m32();
                float we = mv.m03() * x + mv.m13() * y + mv.m23() * z + mv.m33();

                // GL_EYE_LINEAR: (s, t, r) = plane . (xe, ye, ze, we), q stays 1.
                float s = planeS[0] * xe + planeS[1] * ye + planeS[2] * ze + planeS[3] * we;
                float t = planeT[0] * xe + planeT[1] * ye + planeT[2] * ze + planeT[3] * we;
                float r = planeR[0] * xe + planeR[1] * ye + planeR[2] * ze + planeR[3] * we;

                // The generated coordinate passes through the baked texture matrix; the
                // bake makes w perspective, and GL_TEXTURE_2D divides (u, v) by it.
                float u = texMat.m00() * s + texMat.m10() * t + texMat.m20() * r + texMat.m30();
                float v = texMat.m01() * s + texMat.m11() * t + texMat.m21() * r + texMat.m31();
                float w = texMat.m03() * s + texMat.m13() * t + texMat.m23() * r + texMat.m33();
                if (w != 0.0f) {
                    u /= w;
                    v /= w;
                }

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
