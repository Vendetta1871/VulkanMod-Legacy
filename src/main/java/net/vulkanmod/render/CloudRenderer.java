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
import net.vulkanmod.vulkan.memory.AutoIndexBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.VertexBuffer;
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

    // Persistent device-side copies of the constant cloud geometry (M12): each grid is
    // built once; the per-frame scroll/camera offset rides the modelview (XZ/Y) and the
    // UvOffset UBO field (UV), the cloud colour the ColorModulator - nothing is rebuilt
    // or re-uploaded per frame anymore.
    private static VertexBuffer cloudVbo;
    private static VertexBuffer cloudFancyVbo;
    private static int cloudFancyVerts;

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
        // The fancy prepass sets colorMask(0); a throw before the colour pass would leave
        // it there and EntityRenderer.capture would drop every entity draw (R19).
        int colorMask = VRenderSystem.colorMask;
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
            VRenderSystem.colorMask = colorMask;
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

        // renderSky restores depthMask(true), so the clouds write depth just like
        // vanilla. Depth comes from the GL-truth mirror (N1), not a glGet* that hands
        // back garbage when the context does not answer (R24).
        VRenderSystem.depthMask(VRenderSystem.glDepthMask);
        if (VRenderSystem.glDepthTest) {
            VRenderSystem.enableDepthTest();
        } else {
            VRenderSystem.disableDepthTest();
        }
        VRenderSystem.depthFunc(VRenderSystem.glDepthFun);

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

        // The cloud colour rides the ColorModulator; the vertices carry only the face
        // shading factors (M12), so the fragment multiplies colour x shading x texture
        // exactly like vanilla's per-vertex product.
        VRenderSystem.setShaderColor(r, g, b, 1.0f);
        VRenderSystem.setShaderPipeline(cloudPipeline);
        // SRC_ALPHA / ONE_MINUS_SRC_ALPHA, matching vanilla's tryBlendFuncSeparate.
        Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
        VTextureSelector.bindTexture(cloudTexture);

        if (mc.gameSettings.shouldRenderClouds() == 2) {
            // renderCloudsFancy scales the modelview by (12,1,12) and builds each cell
            // in that squashed space; the fog distance the shader derives from the
            // view-space position therefore matches the GL fog coordinate.
            double d0 = (float) cloudTickCounter + partialTicks;
            double d1 = (eyeX + d0 * 0.03) / 12.0;
            double d2 = eyeZ / 12.0 + 0.33;
            float f2 = world.provider.getCloudHeight() - (float) eyeY + 0.33f;
            int ci = MathHelper.floor(d1 / 2048.0);
            int cj = MathHelper.floor(d2 / 2048.0);
            d1 -= (double) (ci * 2048);
            d2 -= (double) (cj * 2048);

            // The baked volume sits on the cell grid at y=0; the fractional scroll/
            // camera offset and the layer height ride the modelview, the integer cell
            // UV offset rides UvOffset (M12).
            Matrix4f mv = new Matrix4f(headMV);
            mv.scale(12.0f, 1.0f, 12.0f);
            mv.translate(-(float) (d1 - MathHelper.floor(d1)), f2, -(float) (d2 - MathHelper.floor(d2)));
            VRenderSystem.applyModelViewMatrix(mv);
            VRenderSystem.calculateMVP();
            VRenderSystem.setCloudUvOffset(MathHelper.floor(d1) * 0.00390625f,
                    MathHelper.floor(d2) * 0.00390625f);

            if (cloudFancyVbo == null) {
                cloudFancyVerts = buildFancyClouds();
                cloudFancyVbo = uploadStatic(cloudBuffer, cloudFancyVerts, POSITION_TEX_COLOR);
            }
            // Two passes like vanilla: a colorMask-off depth prepass writes the depth
            // of the near faces first, so the coloured pass rejects the semi-transparent
            // side fragments hidden behind them instead of double-blending them.
            VRenderSystem.colorMask(false, false, false, false);
            drawStaticQuads(drawer, cloudFancyVbo, cloudFancyVerts);
            VRenderSystem.colorMask(true, true, true, true);
            drawStaticQuads(drawer, cloudFancyVbo, cloudFancyVerts);
        } else {
            // The fast grid is baked at y=0 with cell-local UVs; the layer height rides
            // the modelview and the scroll/camera UV offset UvOffset (M12).
            double scroll = (float) cloudTickCounter + partialTicks;
            double d0 = eyeX + scroll * 0.03;
            int k = MathHelper.floor(d0 / 2048.0);
            int l = MathHelper.floor(eyeZ / 2048.0);
            d0 -= (double) (k * 2048);
            double d1 = eyeZ - (double) (l * 2048);
            float f6 = world.provider.getCloudHeight() - (float) eyeY + 0.33f;

            Matrix4f mv = new Matrix4f(headMV);
            mv.translate(0.0f, f6, 0.0f);
            VRenderSystem.applyModelViewMatrix(mv);
            VRenderSystem.calculateMVP();
            VRenderSystem.setCloudUvOffset((float) (d0 * 4.8828125E-4), (float) (d1 * 4.8828125E-4));

            if (cloudVbo == null) {
                buildClouds();
                cloudVbo = uploadStatic(cloudBuffer, CLOUD_VERTS, POSITION_TEX_COLOR);
            }
            drawStaticQuads(drawer, cloudVbo, CLOUD_VERTS);
        }
        return true;
    }

    /**
     * Builds the fast-cloud grid once (vanilla's 16x16 quads of 32x32 units): the y and
     * UV offsets the per-frame scroll/camera motion used to bake into every vertex are
     * now applied by the modelview and UvOffset (M12), so the baked plane sits at y=0
     * with cell-local UVs and a plain white vertex colour (the colour rides the
     * ColorModulator).
     */
    private static void buildClouds() {
        ByteBuffer buf = cloudBuffer;
        buf.position(0);
        for (int i1 = -256; i1 < 256; i1 += 32) {
            for (int j1 = -256; j1 < 256; j1 += 32) {
                putPosUvColor(buf, i1, 0.0f, j1 + 32,
                        i1 * 4.8828125E-4f, (j1 + 32) * 4.8828125E-4f, 1.0f, 1.0f, 1.0f, 0.8f);
                putPosUvColor(buf, i1 + 32, 0.0f, j1 + 32,
                        (i1 + 32) * 4.8828125E-4f, (j1 + 32) * 4.8828125E-4f, 1.0f, 1.0f, 1.0f, 0.8f);
                putPosUvColor(buf, i1 + 32, 0.0f, j1,
                        (i1 + 32) * 4.8828125E-4f, j1 * 4.8828125E-4f, 1.0f, 1.0f, 1.0f, 0.8f);
                putPosUvColor(buf, i1, 0.0f, j1,
                        i1 * 4.8828125E-4f, j1 * 4.8828125E-4f, 1.0f, 1.0f, 1.0f, 0.8f);
            }
        }
        buf.position(0);
    }

    /**
     * Builds the fancy-cloud volume once (vanilla's 8x8 cells of 8x8 units in the
     * {@code scale(12,1,12)} space, each extruded from 0 to 4): the bottom face is
     * shaded to 0.7, the top to full and the X/Z sides to 0.9/0.8 - as pure grey
     * factors, the cloud colour rides the ColorModulator (M12). The UV is the cell
     * coordinate scaled by 1/256; the scroll offset rides UvOffset. Both the bottom and
     * the top face are always baked: vanilla skips one of them only when the camera is
     * far from the layer, where the depth prepass of the nearer faces hides the skipped
     * one anyway. Returns the vertex count.
     */
    private static int buildFancyClouds() {
        // Face shading factors; the colour itself is the ColorModulator (M12).
        float bottom = 0.7f, xSide = 0.9f, zSide = 0.8f;

        ByteBuffer buf = cloudBuffer;
        buf.position(0);
        for (int j1 = -3; j1 <= 4; ++j1) {
            for (int k1 = -3; k1 <= 4; ++k1) {
                float f21 = j1 * 8;
                float f22 = k1 * 8;
                putPosUvColor(buf, f21, 0.0f, f22 + 8, f21 * 0.00390625f, (f22 + 8) * 0.00390625f, bottom, bottom, bottom, 0.8f);
                putPosUvColor(buf, f21 + 8, 0.0f, f22 + 8, (f21 + 8) * 0.00390625f, (f22 + 8) * 0.00390625f, bottom, bottom, bottom, 0.8f);
                putPosUvColor(buf, f21 + 8, 0.0f, f22, (f21 + 8) * 0.00390625f, f22 * 0.00390625f, bottom, bottom, bottom, 0.8f);
                putPosUvColor(buf, f21, 0.0f, f22, f21 * 0.00390625f, f22 * 0.00390625f, bottom, bottom, bottom, 0.8f);

                float top = 4.0f - 9.765625E-4f;
                putPosUvColor(buf, f21, top, f22 + 8, f21 * 0.00390625f, (f22 + 8) * 0.00390625f, 1.0f, 1.0f, 1.0f, 0.8f);
                putPosUvColor(buf, f21 + 8, top, f22 + 8, (f21 + 8) * 0.00390625f, (f22 + 8) * 0.00390625f, 1.0f, 1.0f, 1.0f, 0.8f);
                putPosUvColor(buf, f21 + 8, top, f22, (f21 + 8) * 0.00390625f, f22 * 0.00390625f, 1.0f, 1.0f, 1.0f, 0.8f);
                putPosUvColor(buf, f21, top, f22, f21 * 0.00390625f, f22 * 0.00390625f, 1.0f, 1.0f, 1.0f, 0.8f);

                if (j1 > -1) {
                    for (int l1 = 0; l1 < 8; ++l1) {
                        float u = (f21 + l1 + 0.5f) * 0.00390625f;
                        float x = f21 + l1;
                        putPosUvColor(buf, x, 0.0f, f22 + 8, u, (f22 + 8) * 0.00390625f, xSide, xSide, xSide, 0.8f);
                        putPosUvColor(buf, x, 4.0f, f22 + 8, u, (f22 + 8) * 0.00390625f, xSide, xSide, xSide, 0.8f);
                        putPosUvColor(buf, x, 4.0f, f22, u, f22 * 0.00390625f, xSide, xSide, xSide, 0.8f);
                        putPosUvColor(buf, x, 0.0f, f22, u, f22 * 0.00390625f, xSide, xSide, xSide, 0.8f);
                    }
                }
                if (j1 <= 1) {
                    for (int i2 = 0; i2 < 8; ++i2) {
                        float u = (f21 + i2 + 0.5f) * 0.00390625f;
                        float x = f21 + i2 + 1.0f - 9.765625E-4f;
                        putPosUvColor(buf, x, 0.0f, f22 + 8, u, (f22 + 8) * 0.00390625f, xSide, xSide, xSide, 0.8f);
                        putPosUvColor(buf, x, 4.0f, f22 + 8, u, (f22 + 8) * 0.00390625f, xSide, xSide, xSide, 0.8f);
                        putPosUvColor(buf, x, 4.0f, f22, u, f22 * 0.00390625f, xSide, xSide, xSide, 0.8f);
                        putPosUvColor(buf, x, 0.0f, f22, u, f22 * 0.00390625f, xSide, xSide, xSide, 0.8f);
                    }
                }
                if (k1 > -1) {
                    for (int j2 = 0; j2 < 8; ++j2) {
                        float v = (f22 + j2 + 0.5f) * 0.00390625f;
                        float z = f22 + j2;
                        putPosUvColor(buf, f21, 4.0f, z, f21 * 0.00390625f, v, zSide, zSide, zSide, 0.8f);
                        putPosUvColor(buf, f21 + 8, 4.0f, z, (f21 + 8) * 0.00390625f, v, zSide, zSide, zSide, 0.8f);
                        putPosUvColor(buf, f21 + 8, 0.0f, z, (f21 + 8) * 0.00390625f, v, zSide, zSide, zSide, 0.8f);
                        putPosUvColor(buf, f21, 0.0f, z, f21 * 0.00390625f, v, zSide, zSide, zSide, 0.8f);
                    }
                }
                if (k1 <= 1) {
                    for (int k2 = 0; k2 < 8; ++k2) {
                        float v = (f22 + k2 + 0.5f) * 0.00390625f;
                        float z = f22 + k2 + 1.0f - 9.765625E-4f;
                        putPosUvColor(buf, f21, 4.0f, z, f21 * 0.00390625f, v, zSide, zSide, zSide, 0.8f);
                        putPosUvColor(buf, f21 + 8, 4.0f, z, (f21 + 8) * 0.00390625f, v, zSide, zSide, zSide, 0.8f);
                        putPosUvColor(buf, f21 + 8, 0.0f, z, (f21 + 8) * 0.00390625f, v, zSide, zSide, zSide, 0.8f);
                        putPosUvColor(buf, f21, 0.0f, z, f21 * 0.00390625f, v, zSide, zSide, zSide, 0.8f);
                    }
                }
            }
        }
        int count = buf.position() / POSITION_TEX_COLOR.getSize();
        buf.position(0);
        return count;
    }

    /** Uploads freshly built constant geometry into a persistent device-local VBO. */
    private static VertexBuffer uploadStatic(ByteBuffer data, int vertexCount, VertexFormat format) {
        int size = format.getSize();
        VertexBuffer vbo = new VertexBuffer(size * vertexCount, MemoryTypes.GPU_MEM);
        data.position(0);
        vbo.copyToVertexBuffer(size, vertexCount, data);
        return vbo;
    }

    /** Indexed-quad draw of a persistent VBO through the shared quad index buffer. */
    private static void drawStaticQuads(Drawer drawer, VertexBuffer vbo, int vertexCount) {
        AutoIndexBuffer quads = drawer.getQuadsIndexBuffer();
        drawer.draw(vbo, quads.getIndexBuffer(vertexCount), vertexCount * 3 / 2, 7);
    }

    // --- cloud texture ---

    /** Resource reload: drop the cached cloud image and any earlier failure (R20). Deferred free. */
    public static void onResourceManagerReload(net.minecraft.client.resources.IResourceManager manager) {
        VulkanImage old = cloudTexture;
        cloudTexture = null;
        textureFailed = false;
        if (old != null) {
            old.free();
        }
    }

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
