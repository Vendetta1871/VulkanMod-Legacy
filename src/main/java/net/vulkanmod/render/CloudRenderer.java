package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Vulkan port of the 1.12.2 cloud layer, {@code RenderGlobal.renderClouds}. Both vanilla
 * paths are ported: the fast path ({@code clouds == 1}) builds a 16x16 grid of 32x32
 * quads at {@code y = cloudHeight - eyeY + 0.33}, and the fancy path ({@code clouds == 2},
 * the default) extrudes each cell into a shaded box (bottom, top and the four sides) in
 * the {@code scale(12,1,12)} space vanilla uses, drawn twice - a {@code colorMask}-off
 * depth prepass so the near faces occlude the semi-transparent sides behind them, then
 * the coloured pass. Both are built in eye-relative space and drawn with a rotation-only
 * modelview, so they follow the camera horizontally while the texture scroll keeps the
 * cloud field anchored in world space.
 */
public class CloudRenderer {

    private static Pipeline cloudPipeline; // POSITION_TEX_COLOR: texture * vertex color + fog

    private static ByteBuffer cloudBuffer;

    private static VulkanImage cloudTexture;
    private static boolean textureFailed;

    private static final VertexFormat POSITION_TEX_COLOR = DefaultVertexFormats.POSITION_TEX_COLOR;

    /** 16x16 quads, 4 vertices each (the vanilla fast-cloud loop). */
    private static final int CLOUD_VERTS = 16 * 16 * 4;

    /**
     * Upper bound on the fancy-cloud vertex count: 8x8 cells, each at most bottom(4) +
     * top(4) + four side strips of 8 quads (4x32). The buffer is sized for this so both
     * paths share one allocation.
     */
    private static final int FANCY_VERTS_MAX = 64 * (4 + 4 + 4 * 32);

    private static final FloatBuffer float4 = MemoryUtil.memAllocFloat(4);
    private static final FloatBuffer float1 = MemoryUtil.memAllocFloat(1);
    private static final ByteBuffer bool1 = MemoryUtil.memAlloc(1);

    public static void init() {
        cloudPipeline = new Pipeline(POSITION_TEX_COLOR, "clouds");
        cloudBuffer = MemoryUtil.memAlloc(FANCY_VERTS_MAX * POSITION_TEX_COLOR.getSize());
    }

    /**
     * Called at the head of {@code RenderGlobal.renderClouds}, before the GL clouds run.
     * The eye coordinates and cloud tick counter are the ones vanilla is about to use.
     *
     * @return true when the Vulkan clouds replaced the vanilla GL layer, so the caller
     *         can cancel {@code RenderGlobal.renderClouds}; false when the GL path has
     *         to run (custom cloud renderer, inactive renderer, texture load failure).
     */
    public static boolean render(float partialTicks, int pass, int cloudTickCounter,
                                 double eyeX, double eyeY, double eyeZ) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null || !Drawer.isActive() || Drawer.skipRendering) {
            return false;
        }
        if (!world.provider.isSurfaceWorld()) {
            return false;
        }
        // A modded cloud renderer draws its own GL geometry; like the sky renderer it
        // cannot be replayed generically, so the GL path stays.
        if (world.provider.getCloudRenderer() != null) {
            return false;
        }

        boolean drawn = false;
        // Vanilla's renderClouds ends with enableCull()/depthMask(true), which cancelling
        // it skips; the pass's own disableCull() would leak into terrain and entities (N1).
        boolean depthTest = VRenderSystem.depthTest;
        boolean depthMask = VRenderSystem.depthMask;
        int depthFun = VRenderSystem.depthFun;
        boolean cull = VRenderSystem.cull;
        try {
            drawn = renderClouds(mc, world, partialTicks, pass, cloudTickCounter, eyeX, eyeY, eyeZ);
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("clouds", t);
        } finally {
            // The terrain pass reads the blend state and the Sampler0 binding as-is at
            // draw time, so restore both the way the sky pass does.
            Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
            VRenderSystem.depthTest = depthTest;
            VRenderSystem.depthMask = depthMask;
            VRenderSystem.depthFun = depthFun;
            VRenderSystem.cull = cull;
            TextureBridge.restoreAtlasBinding();
        }
        return drawn;
    }

    private static boolean renderClouds(Minecraft mc, World world, float partialTicks, int pass,
                                        int cloudTickCounter, double eyeX, double eyeY, double eyeZ) {
        if (!ensureCloudTexture()) {
            return false;
        }

        Drawer drawer = Drawer.getInstance();

        // --- state mirrored from GL at the head of renderClouds ---
        VRenderSystem.updateProjectionMatrix();
        VRenderSystem.updateModelViewMatrix();
        Matrix4f headMV = new Matrix4f(VRenderSystem.getModelViewMatrix4f());
        // Same camera-relative space as the sky: the modelview at renderClouds is the
        // camera rotation only (the -eye translate happens later, per block layer), and
        // the cloud vertices are already eye-relative, so zero the residual translation
        // Kirino reports and keep every vertex in that same camera-relative space.
        headMV.set(0, 3, 0.0f).set(1, 3, 0.0f).set(2, 3, 0.0f);

        snapshotFog();

        // renderSky restores depthMask(true), so the clouds write depth just like vanilla.
        VRenderSystem.depthMask(boolState(GL11.GL_DEPTH_WRITEMASK));
        if (boolState(GL11.GL_DEPTH_TEST)) {
            VRenderSystem.enableDepthTest();
        } else {
            VRenderSystem.disableDepthTest();
        }
        GL11.glGetIntegerv(GL11.GL_DEPTH_FUNC, (java.nio.IntBuffer) int1());
        VRenderSystem.depthFunc(int1().get(0));

        // Vanilla disables culling for the whole cloud draw (the flat quads are seen from
        // both sides). At this hook point the live GL cull state is still the enabled one
        // renderWorldPass left behind, so mirror vanilla's intent, not the current state.
        VRenderSystem.disableCull();

        // Cloud colour: world.getCloudColour, desaturated for the anaglyph passes.
        Vec3d cloud = world.getCloudColour(partialTicks);
        float r = (float) cloud.x, g = (float) cloud.y, b = (float) cloud.z;
        if (pass != 2) {
            float[] desat = desaturate(r, g, b);
            r = desat[0]; g = desat[1]; b = desat[2];
        }

        VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        VRenderSystem.setShaderPipeline(cloudPipeline);
        // SRC_ALPHA / ONE_MINUS_SRC_ALPHA, matching vanilla's tryBlendFuncSeparate.
        Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
        VTextureSelector.bindTexture(cloudTexture);

        if (mc.gameSettings.shouldRenderClouds() == 2) {
            // renderCloudsFancy scales the modelview by (12,1,12) and builds each cell
            // in that squashed space; the fog distance the shader derives from the
            // view-space position therefore matches the GL fog coordinate.
            Matrix4f mv = new Matrix4f(headMV);
            mv.scale(12.0f, 1.0f, 12.0f);
            VRenderSystem.applyModelViewMatrix(mv);
            VRenderSystem.calculateMVP();

            int vertCount = buildFancyClouds(world, partialTicks, cloudTickCounter,
                    eyeX, eyeY, eyeZ, r, g, b);
            if (vertCount > 0) {
                // Two passes like vanilla: a colorMask-off depth prepass writes the depth
                // of the near faces first, so the coloured pass rejects the semi-transparent
                // side fragments hidden behind them instead of double-blending them.
                VRenderSystem.colorMask(false, false, false, false);
                drawer.draw(cloudBuffer, 7, POSITION_TEX_COLOR, vertCount);
                VRenderSystem.colorMask(true, true, true, true);
                drawer.draw(cloudBuffer, 7, POSITION_TEX_COLOR, vertCount);
            }
        } else {
            VRenderSystem.applyModelViewMatrix(headMV);
            VRenderSystem.calculateMVP();
            buildClouds(world, partialTicks, cloudTickCounter, eyeX, eyeY, eyeZ, r, g, b);
            drawer.draw(cloudBuffer, 7, POSITION_TEX_COLOR, CLOUD_VERTS);
        }
        return true;
    }

    /**
     * Refills the cloud grid, replicating the vanilla fast-cloud loop: 16x16 quads of
     * 32x32 units in eye-relative XZ, at {@code f6 = cloudHeight - eyeY + 0.33}. The UV
     * is the eye-relative position scaled by 1/2048 plus the scroll offset, so the tiled
     * texture is anchored in world space and drifts with {@code cloudTickCounter}.
     */
    private static void buildClouds(World world, float partialTicks, int cloudTickCounter,
                                    double eyeX, double eyeY, double eyeZ,
                                    float r, float g, float b) {
        double scroll = (float) cloudTickCounter + partialTicks;
        double d0 = eyeX + scroll * 0.03;
        int k = MathHelper.floor(d0 / 2048.0);
        int l = MathHelper.floor(eyeZ / 2048.0);
        d0 -= (double) (k * 2048);
        double d1 = eyeZ - (double) (l * 2048);
        float f6 = world.provider.getCloudHeight() - (float) eyeY + 0.33f;
        float f7 = (float) (d0 * 4.8828125E-4);
        float f8 = (float) (d1 * 4.8828125E-4);

        ByteBuffer buf = cloudBuffer;
        buf.position(0);
        for (int i1 = -256; i1 < 256; i1 += 32) {
            for (int j1 = -256; j1 < 256; j1 += 32) {
                putPosUvColor(buf, i1, f6, j1 + 32,
                        i1 * 4.8828125E-4f + f7, (j1 + 32) * 4.8828125E-4f + f8, r, g, b, 0.8f);
                putPosUvColor(buf, i1 + 32, f6, j1 + 32,
                        (i1 + 32) * 4.8828125E-4f + f7, (j1 + 32) * 4.8828125E-4f + f8, r, g, b, 0.8f);
                putPosUvColor(buf, i1 + 32, f6, j1,
                        (i1 + 32) * 4.8828125E-4f + f7, j1 * 4.8828125E-4f + f8, r, g, b, 0.8f);
                putPosUvColor(buf, i1, f6, j1,
                        i1 * 4.8828125E-4f + f7, j1 * 4.8828125E-4f + f8, r, g, b, 0.8f);
            }
        }
        buf.position(0);
    }

    /**
     * Refills the buffer with the fancy-cloud volume, replicating the vanilla
     * {@code renderCloudsFancy} loop: 8x8 cells of 8x8 units (in the {@code scale(12,1,12)}
     * space the caller set up), each extruded from {@code f2} to {@code f2+4}. The bottom
     * face is shaded to 0.7, the top to full colour and the X/Z sides to 0.9/0.8; the UV
     * is the cell coordinate scaled by 1/256 plus the wrapped scroll offset. Returns the
     * number of vertices written (0 when the layer is entirely out of range).
     */
    private static int buildFancyClouds(World world, float partialTicks, int cloudTickCounter,
                                        double eyeX, double eyeY, double eyeZ,
                                        float r, float g, float b) {
        double d0 = (float) cloudTickCounter + partialTicks;
        double d1 = (eyeX + d0 * 0.03) / 12.0;
        double d2 = eyeZ / 12.0 + 0.33;
        float f2 = world.provider.getCloudHeight() - (float) eyeY + 0.33f;
        int i = MathHelper.floor(d1 / 2048.0);
        int j = MathHelper.floor(d2 / 2048.0);
        d1 -= (double) (i * 2048);
        d2 -= (double) (j * 2048);

        // Face shading factors relative to the (already desaturated) cloud colour.
        float br = r * 0.7f, bg = g * 0.7f, bb = b * 0.7f;   // bottom
        float xr = r * 0.9f, xg = g * 0.9f, xb = b * 0.9f;   // +/-X sides
        float zr = r * 0.8f, zg = g * 0.8f, zb = b * 0.8f;   // +/-Z sides

        float f16 = MathHelper.floor(d1) * 0.00390625f;
        float f17 = MathHelper.floor(d2) * 0.00390625f;
        float f18 = (float) (d1 - MathHelper.floor(d1));
        float f19 = (float) (d2 - MathHelper.floor(d2));

        ByteBuffer buf = cloudBuffer;
        buf.position(0);
        for (int j1 = -3; j1 <= 4; ++j1) {
            for (int k1 = -3; k1 <= 4; ++k1) {
                float f21 = j1 * 8;
                float f22 = k1 * 8;
                float f23 = f21 - f18;
                float f24 = f22 - f19;
                if (f2 > -5.0f) {
                    putPosUvColor(buf, f23, f2, f24 + 8, f21 * 0.00390625f + f16, (f22 + 8) * 0.00390625f + f17, br, bg, bb, 0.8f);
                    putPosUvColor(buf, f23 + 8, f2, f24 + 8, (f21 + 8) * 0.00390625f + f16, (f22 + 8) * 0.00390625f + f17, br, bg, bb, 0.8f);
                    putPosUvColor(buf, f23 + 8, f2, f24, (f21 + 8) * 0.00390625f + f16, f22 * 0.00390625f + f17, br, bg, bb, 0.8f);
                    putPosUvColor(buf, f23, f2, f24, f21 * 0.00390625f + f16, f22 * 0.00390625f + f17, br, bg, bb, 0.8f);
                }
                if (f2 <= 5.0f) {
                    float top = f2 + 4.0f - 9.765625E-4f;
                    putPosUvColor(buf, f23, top, f24 + 8, f21 * 0.00390625f + f16, (f22 + 8) * 0.00390625f + f17, r, g, b, 0.8f);
                    putPosUvColor(buf, f23 + 8, top, f24 + 8, (f21 + 8) * 0.00390625f + f16, (f22 + 8) * 0.00390625f + f17, r, g, b, 0.8f);
                    putPosUvColor(buf, f23 + 8, top, f24, (f21 + 8) * 0.00390625f + f16, f22 * 0.00390625f + f17, r, g, b, 0.8f);
                    putPosUvColor(buf, f23, top, f24, f21 * 0.00390625f + f16, f22 * 0.00390625f + f17, r, g, b, 0.8f);
                }
                if (j1 > -1) {
                    for (int l1 = 0; l1 < 8; ++l1) {
                        float u = (f21 + l1 + 0.5f) * 0.00390625f + f16;
                        float x = f23 + l1;
                        putPosUvColor(buf, x, f2, f24 + 8, u, (f22 + 8) * 0.00390625f + f17, xr, xg, xb, 0.8f);
                        putPosUvColor(buf, x, f2 + 4.0f, f24 + 8, u, (f22 + 8) * 0.00390625f + f17, xr, xg, xb, 0.8f);
                        putPosUvColor(buf, x, f2 + 4.0f, f24, u, f22 * 0.00390625f + f17, xr, xg, xb, 0.8f);
                        putPosUvColor(buf, x, f2, f24, u, f22 * 0.00390625f + f17, xr, xg, xb, 0.8f);
                    }
                }
                if (j1 <= 1) {
                    for (int i2 = 0; i2 < 8; ++i2) {
                        float u = (f21 + i2 + 0.5f) * 0.00390625f + f16;
                        float x = f23 + i2 + 1.0f - 9.765625E-4f;
                        putPosUvColor(buf, x, f2, f24 + 8, u, (f22 + 8) * 0.00390625f + f17, xr, xg, xb, 0.8f);
                        putPosUvColor(buf, x, f2 + 4.0f, f24 + 8, u, (f22 + 8) * 0.00390625f + f17, xr, xg, xb, 0.8f);
                        putPosUvColor(buf, x, f2 + 4.0f, f24, u, f22 * 0.00390625f + f17, xr, xg, xb, 0.8f);
                        putPosUvColor(buf, x, f2, f24, u, f22 * 0.00390625f + f17, xr, xg, xb, 0.8f);
                    }
                }
                if (k1 > -1) {
                    for (int j2 = 0; j2 < 8; ++j2) {
                        float v = (f22 + j2 + 0.5f) * 0.00390625f + f17;
                        float z = f24 + j2;
                        putPosUvColor(buf, f23, f2 + 4.0f, z, f21 * 0.00390625f + f16, v, zr, zg, zb, 0.8f);
                        putPosUvColor(buf, f23 + 8, f2 + 4.0f, z, (f21 + 8) * 0.00390625f + f16, v, zr, zg, zb, 0.8f);
                        putPosUvColor(buf, f23 + 8, f2, z, (f21 + 8) * 0.00390625f + f16, v, zr, zg, zb, 0.8f);
                        putPosUvColor(buf, f23, f2, z, f21 * 0.00390625f + f16, v, zr, zg, zb, 0.8f);
                    }
                }
                if (k1 <= 1) {
                    for (int k2 = 0; k2 < 8; ++k2) {
                        float v = (f22 + k2 + 0.5f) * 0.00390625f + f17;
                        float z = f24 + k2 + 1.0f - 9.765625E-4f;
                        putPosUvColor(buf, f23, f2 + 4.0f, z, f21 * 0.00390625f + f16, v, zr, zg, zb, 0.8f);
                        putPosUvColor(buf, f23 + 8, f2 + 4.0f, z, (f21 + 8) * 0.00390625f + f16, v, zr, zg, zb, 0.8f);
                        putPosUvColor(buf, f23 + 8, f2, z, (f21 + 8) * 0.00390625f + f16, v, zr, zg, zb, 0.8f);
                        putPosUvColor(buf, f23, f2, z, f21 * 0.00390625f + f16, v, zr, zg, zb, 0.8f);
                    }
                }
            }
        }
        int count = buf.position() / POSITION_TEX_COLOR.getSize();
        buf.position(0);
        return count;
    }

    // --- cloud texture ---

    private static boolean ensureCloudTexture() {
        if (cloudTexture != null) {
            return true;
        }
        if (textureFailed) {
            return false;
        }
        try {
            cloudTexture = loadTexture("textures/environment/clouds.png");
            Initializer.LOGGER.info("[clouds] cloud texture uploaded");
            return true;
        } catch (Exception e) {
            textureFailed = true;
            Initializer.LOGGER.error("[clouds] cannot load the cloud texture", e);
            return false;
        }
    }

    /**
     * Uploads an environment texture. Unlike the sun/moon the cloud UV tiles past 1, so
     * the sampler wraps (clamp = false); vanilla uploads clouds.png without mcmeta, so
     * NEAREST filtering and no mipmaps.
     */
    private static VulkanImage loadTexture(String path) throws Exception {
        net.minecraft.client.resources.IResource resource = Minecraft.getMinecraft()
                .getResourceManager().getResource(new ResourceLocation(path));

        BufferedImage image;
        try (InputStream stream = resource.getInputStream()) {
            image = ImageIO.read(stream);
        }
        if (image == null) {
            throw new java.io.IOException("cannot decode " + path);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int argb = image.getRGB(x, y);
                pixels.put((byte) (argb >> 16));
                pixels.put((byte) (argb >> 8));
                pixels.put((byte) argb);
                pixels.put((byte) (argb >>> 24));
            }
        }
        pixels.position(0);
        pixels.limit(width * height * 4);

        VulkanImage texture = new VulkanImage(1, width, height, 4, false, false);
        texture.uploadSubTextureAsync(0, width, height, 0, 0, 4, 0, 0, width, pixels);
        MemoryUtil.memFree(pixels);
        return texture;
    }

    // --- helpers (mirroring the SkyRenderer ones; kept local so the renderers stay
    //     self-contained the way TerrainRenderer and SkyRenderer each are) ---

    private static float[] desaturate(float r, float g, float b) {
        return new float[] {
                (r * 30.0f + g * 59.0f + b * 11.0f) / 100.0f,
                (r * 30.0f + g * 70.0f) / 100.0f,
                (r * 30.0f + b * 70.0f) / 100.0f
        };
    }

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

    private static boolean boolState(int pname) {
        GL11.glGetBooleanv(pname, bool1);
        return bool1.get(0) != 0;
    }

    private static void putPosUvColor(ByteBuffer buf, float x, float y, float z,
                                      float u, float v, float r, float g, float b, float a) {
        buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        buf.put((byte) (clamp01(r) * 255.0f));
        buf.put((byte) (clamp01(g) * 255.0f));
        buf.put((byte) (clamp01(b) * 255.0f));
        buf.put((byte) (clamp01(a) * 255.0f));
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private CloudRenderer() {
    }
}
