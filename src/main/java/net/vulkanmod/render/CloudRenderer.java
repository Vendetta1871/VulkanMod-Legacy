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
 * Vulkan port of the 1.12.2 cloud layer, {@code RenderGlobal.renderClouds} fast path
 * (the {@code gameSettings.clouds == 1} branch). The grid is built in eye-relative
 * space exactly like vanilla - a 16x16 grid of 32x32 quads at
 * {@code y = cloudHeight - eyeY + 0.33}, tiled with the scrolling {@code clouds.png}
 * - and drawn with a rotation-only modelview, so it follows the camera horizontally
 * while the texture scroll keeps it anchored in world space.
 *
 * <p>The fancy path ({@code clouds == 2}, the vanilla default) extrudes each cell into
 * a shaded box and uses a two-pass {@code colorMask} depth prepass to keep the
 * semi-transparent sides from double-blending; it is not ported yet, so the fast path
 * is used for both settings until then.</p>
 */
public class CloudRenderer {

    private static Pipeline cloudPipeline; // POSITION_TEX_COLOR: texture * vertex color + fog

    private static ByteBuffer cloudBuffer;

    private static VulkanImage cloudTexture;
    private static boolean textureFailed;

    private static final VertexFormat POSITION_TEX_COLOR = DefaultVertexFormats.POSITION_TEX_COLOR;

    /** 16x16 quads, 4 vertices each (the vanilla fast-cloud loop). */
    private static final int CLOUD_VERTS = 16 * 16 * 4;

    private static final FloatBuffer float4 = MemoryUtil.memAllocFloat(4);
    private static final FloatBuffer float1 = MemoryUtil.memAllocFloat(1);
    private static final ByteBuffer bool1 = MemoryUtil.memAlloc(1);

    public static void init() {
        cloudPipeline = new Pipeline(POSITION_TEX_COLOR, "clouds");
        cloudBuffer = MemoryUtil.memAlloc(CLOUD_VERTS * POSITION_TEX_COLOR.getSize());
    }

    /**
     * Called at the head of {@code RenderGlobal.renderClouds}, before the GL clouds run.
     * The eye coordinates and cloud tick counter are the ones vanilla is about to use.
     */
    public static void render(float partialTicks, int pass, int cloudTickCounter,
                              double eyeX, double eyeY, double eyeZ) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null || !Drawer.isActive() || Drawer.skipRendering) {
            return;
        }
        if (!world.provider.isSurfaceWorld()) {
            return;
        }

        try {
            renderClouds(mc, world, partialTicks, pass, cloudTickCounter, eyeX, eyeY, eyeZ);
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("clouds", t);
        } finally {
            // The terrain pass reads the blend state and the Sampler0 binding as-is at
            // draw time, so restore both the way the sky pass does.
            Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
            TextureBridge.restoreAtlasBinding();
        }
    }

    private static void renderClouds(Minecraft mc, World world, float partialTicks, int pass,
                                     int cloudTickCounter, double eyeX, double eyeY, double eyeZ) {
        if (!ensureCloudTexture()) {
            return;
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

        VRenderSystem.applyModelViewMatrix(headMV);
        VRenderSystem.calculateMVP();

        buildClouds(world, partialTicks, cloudTickCounter, eyeX, eyeY, eyeZ, r, g, b);
        drawer.draw(cloudBuffer, 7, POSITION_TEX_COLOR, CLOUD_VERTS);
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
