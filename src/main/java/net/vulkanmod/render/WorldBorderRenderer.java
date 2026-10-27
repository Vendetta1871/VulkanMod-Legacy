package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
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
 * Vulkan port of the 1.12.2 world border, {@code RenderGlobal.renderWorldBorder}. The
 * border is four walls of one-block-wide quads from y=0 to y=256 along the border sides
 * that are within the render distance of the player, textured with the scrolling
 * forcefield pattern (u advances 0.5 per block, v spans 128 repeats, both offset by
 * {@code systemTime % 3000 / 3000}) and tinted with the border status colour faded by
 * {@code (1 - closestDistance / renderDistance)^4}. It is drawn additively
 * (SRC_ALPHA / ONE) without depth writes, culling or polygon offset -3/-3, exactly like
 * vanilla; with the additive blend fully transparent texels add nothing, so vanilla's
 * alphaFunc(0.1) discard is a pure optimisation and needs no shader counterpart.
 *
 * <p>The walls are built eye-relative (vanilla's {@code setTranslation(-eye)}) and drawn
 * with a rotation-only modelview, like the sky and cloud passes.
 */
public class WorldBorderRenderer {

    /** POSITION_TEX_COLOR with the clouds shader: ColorModulator * vertex color * texture + fog. */
    private static Pipeline borderPipeline;

    private static ByteBuffer borderBuffer;

    private static VulkanImage forcefieldTexture;
    private static boolean textureFailed;

    private static final VertexFormat POSITION_TEX_COLOR = DefaultVertexFormats.POSITION_TEX_COLOR;

    /** Worst case: 4 walls of 2*32*16 one-block quads (render distance 32), 4 vertices each. */
    private static final int BORDER_VERTS_MAX = 4 * (2 * 32 * 16 + 2) * 4;

    /** Vanilla's tryBlendFuncSeparate(SRC_ALPHA, ONE, ONE, ZERO) - additive. */
    private static final Pipeline.BlendState ADDITIVE_BLEND =
            new Pipeline.BlendState(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);

    private static final FloatBuffer float4 = MemoryUtil.memAllocFloat(4);
    private static final FloatBuffer float1 = MemoryUtil.memAllocFloat(1);

    public static void init() {
        borderPipeline = new Pipeline(POSITION_TEX_COLOR, "clouds", "clouds#border");
        borderBuffer = MemoryUtil.memAlloc(BORDER_VERTS_MAX * POSITION_TEX_COLOR.getSize());
    }

    /**
     * Called at the head of {@code RenderGlobal.renderWorldBorder}, before the GL twin
     * runs. The entity and partial ticks are the ones vanilla is about to use.
     */
    public static void render(Entity entity, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null || entity == null || !Drawer.isActive() || Drawer.skipRendering) {
            return;
        }

        // The depth/cull state is tracked from the vanilla GlStateManager calls; save
        // what the frame had on entry so the restore below cannot hardcode values the
        // following pass (the translucent layer) does not expect.
        boolean depthTest = VRenderSystem.depthTest;
        boolean depthMask = VRenderSystem.depthMask;
        int depthFun = VRenderSystem.depthFun;
        boolean cull = VRenderSystem.cull;

        try {
            renderBorder(mc, world, entity, partialTicks);
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("world border", t);
        } finally {
            // The passes after the border read the blend state and the Sampler0 binding
            // as-is at draw time, so restore them the way the cloud pass does.
            Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
            VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            VRenderSystem.depthMask(depthMask);
            VRenderSystem.depthFunc(depthFun);
            if (depthTest) {
                VRenderSystem.enableDepthTest();
            } else {
                VRenderSystem.disableDepthTest();
            }
            if (cull) {
                VRenderSystem.enableCull();
            } else {
                VRenderSystem.disableCull();
            }
            VRenderSystem.disablePolygonOffset();
            TextureBridge.restoreAtlasBinding();
        }
    }

    private static void renderBorder(Minecraft mc, World world, Entity entity, float partialTicks) {
        WorldBorder border = world.getWorldBorder();
        double d0 = mc.gameSettings.renderDistanceChunks * 16;
        // Vanilla skips when the player is more than a render distance inside all four sides.
        if (entity.posX < border.maxX() - d0 && entity.posX > border.minX() + d0
                && entity.posZ < border.maxZ() - d0 && entity.posZ > border.minZ() + d0) {
            return;
        }
        double d1 = 1.0 - border.getClosestDistance(entity) / d0;
        d1 = Math.pow(d1, 4.0);
        if (d1 <= 0.0) {
            return; // alpha 0 adds nothing under the additive blend
        }

        double d2 = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        double d3 = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        double d4 = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;

        if (!ensureTexture()) {
            return;
        }

        Drawer drawer = Drawer.getInstance();

        VRenderSystem.updateProjectionMatrix();
        VRenderSystem.updateModelViewMatrix();
        // Rotation-only modelview, like the sky and clouds: the wall vertices are built
        // eye-relative (vanilla's setTranslation(-d2, -d3, -d4)), so the residual
        // translation Kirino reports must not be applied on top.
        Matrix4f mv = new Matrix4f(VRenderSystem.getModelViewMatrix4f());
        mv.set(0, 3, 0.0f).set(1, 3, 0.0f).set(2, 3, 0.0f);
        VRenderSystem.applyModelViewMatrix(mv);
        VRenderSystem.calculateMVP();

        snapshotFog();

        // --- state mirrored from vanilla's renderWorldBorder sequence ---
        // Depth test/func are already tracked from the vanilla GlStateManager calls
        // (GlStateManagerDepthM); only the border-specific overrides are applied here.
        VRenderSystem.depthMask(false);
        VRenderSystem.disableCull();

        // Status colour (green normally, red while warning); the alpha is the distance
        // fade, carried by the ColorModulator so it stays float-precise.
        int i = border.getStatus().getColor();
        float r = (float) (i >> 16 & 0xFF) / 255.0f;
        float g = (float) (i >> 8 & 0xFF) / 255.0f;
        float b = (float) (i & 0xFF) / 255.0f;

        VRenderSystem.setShaderColor(r, g, b, (float) d1);
        VRenderSystem.setShaderPipeline(borderPipeline);
        Drawer.currentBlendState = ADDITIVE_BLEND;
        VTextureSelector.bindTexture(forcefieldTexture);

        VRenderSystem.polygonOffset(-3.0f, -3.0f);
        VRenderSystem.enablePolygonOffset();

        int vertCount = buildWalls(border, d0, d2, d3, d4);
        if (vertCount > 0) {
            drawer.draw(borderBuffer, 7, POSITION_TEX_COLOR, vertCount);
        }
    }

    /**
     * Refills the buffer with the four wall strips, replicating the vanilla loops: the
     * X walls span the Z range (and vice versa) clamped to the border extent and the
     * render distance, one quad per block column, positions in eye-relative space.
     * Returns the number of vertices written.
     */
    private static int buildWalls(WorldBorder border, double d0, double eyeX, double eyeY, double eyeZ) {
        float f3 = (float) (Minecraft.getSystemTime() % 3000L) / 3000.0f;
        int quadBytes = 4 * POSITION_TEX_COLOR.getSize();

        ByteBuffer buf = borderBuffer;
        buf.position(0);

        double zMin = Math.max(MathHelper.floor(eyeZ - d0), border.minZ());
        double zMax = Math.min(MathHelper.ceil(eyeZ + d0), border.maxZ());
        if (eyeX > border.maxX() - d0) {
            float u = 0.0f;
            for (double z = zMin; z < zMax && buf.position() + quadBytes <= buf.limit(); z += 1.0) {
                float du = (float) Math.min(1.0, zMax - z) * 0.5f;
                put(buf, border.maxX() - eyeX, 256.0 - eyeY, z - eyeZ, f3 + u, f3);
                put(buf, border.maxX() - eyeX, 256.0 - eyeY, z + Math.min(1.0, zMax - z) - eyeZ, f3 + du + u, f3);
                put(buf, border.maxX() - eyeX, -eyeY, z + Math.min(1.0, zMax - z) - eyeZ, f3 + du + u, f3 + 128.0f);
                put(buf, border.maxX() - eyeX, -eyeY, z - eyeZ, f3 + u, f3 + 128.0f);
                u += 0.5f;
            }
        }
        if (eyeX < border.minX() + d0) {
            float u = 0.0f;
            for (double z = zMin; z < zMax && buf.position() + quadBytes <= buf.limit(); z += 1.0) {
                float du = (float) Math.min(1.0, zMax - z) * 0.5f;
                put(buf, border.minX() - eyeX, 256.0 - eyeY, z - eyeZ, f3 + u, f3);
                put(buf, border.minX() - eyeX, 256.0 - eyeY, z + Math.min(1.0, zMax - z) - eyeZ, f3 + du + u, f3);
                put(buf, border.minX() - eyeX, -eyeY, z + Math.min(1.0, zMax - z) - eyeZ, f3 + du + u, f3 + 128.0f);
                put(buf, border.minX() - eyeX, -eyeY, z - eyeZ, f3 + u, f3 + 128.0f);
                u += 0.5f;
            }
        }

        double xMin = Math.max(MathHelper.floor(eyeX - d0), border.minX());
        double xMax = Math.min(MathHelper.ceil(eyeX + d0), border.maxX());
        if (eyeZ > border.maxZ() - d0) {
            float u = 0.0f;
            for (double x = xMin; x < xMax && buf.position() + quadBytes <= buf.limit(); x += 1.0) {
                float du = (float) Math.min(1.0, xMax - x) * 0.5f;
                put(buf, x - eyeX, 256.0 - eyeY, border.maxZ() - eyeZ, f3 + u, f3);
                put(buf, x + Math.min(1.0, xMax - x) - eyeX, 256.0 - eyeY, border.maxZ() - eyeZ, f3 + du + u, f3);
                put(buf, x + Math.min(1.0, xMax - x) - eyeX, -eyeY, border.maxZ() - eyeZ, f3 + du + u, f3 + 128.0f);
                put(buf, x - eyeX, -eyeY, border.maxZ() - eyeZ, f3 + u, f3 + 128.0f);
                u += 0.5f;
            }
        }
        if (eyeZ < border.minZ() + d0) {
            float u = 0.0f;
            for (double x = xMin; x < xMax && buf.position() + quadBytes <= buf.limit(); x += 1.0) {
                float du = (float) Math.min(1.0, xMax - x) * 0.5f;
                put(buf, x - eyeX, 256.0 - eyeY, border.minZ() - eyeZ, f3 + u, f3);
                put(buf, x + Math.min(1.0, xMax - x) - eyeX, 256.0 - eyeY, border.minZ() - eyeZ, f3 + du + u, f3);
                put(buf, x + Math.min(1.0, xMax - x) - eyeX, -eyeY, border.minZ() - eyeZ, f3 + du + u, f3 + 128.0f);
                put(buf, x - eyeX, -eyeY, border.minZ() - eyeZ, f3 + u, f3 + 128.0f);
                u += 0.5f;
            }
        }

        int count = buf.position() / POSITION_TEX_COLOR.getSize();
        buf.position(0);
        return count;
    }

    // --- forcefield texture ---

    private static boolean ensureTexture() {
        if (forcefieldTexture != null) {
            return true;
        }
        if (textureFailed) {
            return false;
        }
        try {
            forcefieldTexture = loadTexture("textures/misc/forcefield.png");
            Initializer.LOGGER.info("[world border] forcefield texture uploaded");
            return true;
        } catch (Exception e) {
            textureFailed = true;
            Initializer.LOGGER.error("[world border] cannot load the forcefield texture", e);
            return false;
        }
    }

    /**
     * Uploads the forcefield pattern. The wall UV tiles far past 1 (v spans 128), so the
     * sampler wraps; like the clouds texture it is a plain SimpleTexture, so NEAREST
     * filtering and no mipmaps.
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

    // --- helpers (mirroring the CloudRenderer ones; kept local so the renderers stay
    //     self-contained the way TerrainRenderer, SkyRenderer and CloudRenderer each are) ---

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
        java.nio.ByteBuffer bool1 = bool1Buffer;
        if (bool1 == null) {
            bool1 = bool1Buffer = MemoryUtil.memAlloc(1);
        }
        GL11.glGetBooleanv(pname, bool1);
        return bool1.get(0) != 0;
    }

    private static java.nio.ByteBuffer bool1Buffer;

    /** White vertex colour: the wall tint and the distance fade ride on the ColorModulator. */
    private static void put(ByteBuffer buf, double x, double y, double z, float u, float v) {
        buf.putFloat((float) x).putFloat((float) y).putFloat((float) z).putFloat(u).putFloat(v);
        buf.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
    }

    private WorldBorderRenderer() {
    }
}
