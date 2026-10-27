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
 * eye-linear planes - specified by glTexGenfv under that same modelview, so GL's
 * inverse-modelview plane transform cancels the draw-time one - hand back the object
 * position as (s, t, r), and the unit's texture matrix - the mirrored scroll/rotate/scale
 * chain times the projection and modelview the TESR bakes into it with {@code multMatrix}
 * - maps them to the screen-projected (u, v) that
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
    private static final Matrix4f TEX_MAT = new Matrix4f();

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
            // memRealloc instead of memAlloc: growing the scratch used to leak the old
            // buffer (R4).
            scratch = MemoryUtil.memRealloc(scratch, Math.max(vertexCount * outStride, 1024));
        }

        // The replay overrides depth, cull and fog; hand the frame's values back in
        // the finally instead of forcing defaults (R19).
        boolean savedDepthTest = VRenderSystem.depthTest;
        boolean savedDepthMask = VRenderSystem.depthMask;
        int savedDepthFun = VRenderSystem.depthFun;
        boolean savedCull = VRenderSystem.cull;
        int savedFog = VRenderSystem.getFogState();
        try {
            VRenderSystem.updateProjectionMatrix();
            VRenderSystem.updateModelViewMatrix();
            // The TESR runs its whole texture-coordinate chain (scroll translate, rotate,
            // scale) in matrixMode GL_TEXTURE and then bakes the projection and modelview
            // it captured at entry into the texture matrix with two multMatrix calls - the
            // screen-space projection that makes the portal look like a window into space.
            // The mirror tracks the chain but not multMatrix, so fold the same bake in.
            Matrix4f texMat = VRenderSystem.getTextureMatrix4f(TEX_MAT)
                    .mul(SCRATCH_P.set(VRenderSystem.getProjectionMatrix4f()))
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

                // GL_EYE_LINEAR with the planes specified by glTexGenfv under the same
                // modelview that is current at the draw (GL 2.1 §3.8.7): GL stores the
                // plane as (M⁻¹)ᵀ·p, so the generated coordinate is p·(x, y, z, 1) -
                // OBJECT space, not eye space. Evaluating on the eye position instead
                // applied the modelview twice and spun the starfield with the camera (R4).
                float s = planeS[0] * x + planeS[1] * y + planeS[2] * z + planeS[3];
                float t = planeT[0] * x + planeT[1] * y + planeT[2] * z + planeT[3];
                float r = planeR[0] * x + planeR[1] * y + planeR[2] * z + planeR[3];

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

            // Depth and cull from the GL-truth mirror (N1): the TESR runs inside the
            // block-entity pass with the world's depth setup intact, and a direct
            // glGet* can hand back garbage when the context does not answer (R24).
            VRenderSystem.depthTest = VRenderSystem.glDepthTest;
            VRenderSystem.depthMask = VRenderSystem.glDepthMask;
            VRenderSystem.depthFunc(VRenderSystem.glDepthFun);
            VRenderSystem.cull = VRenderSystem.glCull;
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
            VRenderSystem.depthTest = savedDepthTest;
            VRenderSystem.depthMask = savedDepthMask;
            VRenderSystem.depthFun = savedDepthFun;
            VRenderSystem.cull = savedCull;
            VRenderSystem.setFogState(savedFog);
            TextureBridge.restoreAtlasBinding();
        }
    }

    private EndPortalRenderer() {
    }
}
