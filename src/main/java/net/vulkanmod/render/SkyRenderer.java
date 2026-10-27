package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
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
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * Vulkan port of the 1.12.2 overworld sky (see {@code RenderGlobal.renderSky}).
 *
 * <p>Drawn at the head of {@code renderSky}; when it handles the sky the vanilla GL sky
 * is cancelled (see RenderGlobalM). All sky geometry is deterministic or CPU-side, so it
 * is rebuilt here instead of intercepting the GL draws: the fogged sky plane, the
 * smooth-shaded sunrise/sunset glow fan, the sun and moon quads and the star field.</p>
 *
 * <p>The matrices are mirrored from the live GL state exactly like the terrain does:
 * the modelview/projection are read at the head of {@code renderSky} (where the
 * projection is the sky-specific one set up by {@code renderWorldPass}), and the
 * element rotations from the {@code GlStateManager.rotate} sequence inside
 * {@code renderSky} are applied on top with joml.</p>
 */
public class SkyRenderer {

    private static Pipeline skyPipeline;      // POSITION: flat color + fog (plane, stars)
    private static Pipeline skyTexPipeline;   // POSITION_TEX: color * texture (sun, moon)
    private static Pipeline skyVcolPipeline;  // POSITION_COLOR: glow fan

    private static ByteBuffer skyPlaneBuffer;
    private static ByteBuffer skyPlane2Buffer;
    private static ByteBuffer starBuffer;
    private static int starVertexCount;
    private static ByteBuffer sunBuffer;
    private static ByteBuffer moonBuffer;
    private static ByteBuffer fanBuffer;
    /** Vanilla's below-horizon box (5 quads around the eye, L7). */
    private static ByteBuffer belowHorizonBuffer;
    // Scratch for the below-horizon modelviews (L7): applyModelViewMatrix copies the
    // matrix, so one field serves both uses in the frame.
    private static final Matrix4f SCRATCH_HEAD = new Matrix4f();
    private static int builtMoonPhase = -1;

    // Persistent device-side copies of the constant sky geometry (M12): uploaded once,
    // then redrawn from the GPU instead of being rebuilt and re-uploaded every frame.
    private static VertexBuffer skyPlaneVbo;
    private static VertexBuffer skyPlane2Vbo;
    private static VertexBuffer starsVbo;
    private static VertexBuffer endSkyVbo;

    private static VulkanImage sunTexture;
    private static VulkanImage moonTexture;
    private static VulkanImage endSkyTexture;

    /** One face of the End sky cube (the y=-100 square); drawn 6 times, once per rotation. */
    private static ByteBuffer endSkyBuffer;

    private static final VertexFormat POSITION = DefaultVertexFormats.POSITION;
    private static final VertexFormat POSITION_TEX = DefaultVertexFormats.POSITION_TEX;
    private static final VertexFormat POSITION_COLOR = DefaultVertexFormats.POSITION_COLOR;

    /** One 13x13 quad plane (vanilla's glSkyList or glSkyList2), 4 vertices per quad. */
    private static final int SKY_PLANE_VERTS = 13 * 13 * 4;

    // GL_SRC_ALPHA, GL_ONE: additive, like the GL sun/moon. Constant, so it is hoisted out
    // of the per-frame draw (L10) instead of allocating a fresh BlendState every frame.
    private static final Pipeline.BlendState ADDITIVE_BLEND =
            new Pipeline.BlendState(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);

    private static final float PI = (float) Math.PI;
    private static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private static final FloatBuffer float4 = MemoryUtil.memAllocFloat(4);
    private static final FloatBuffer float1 = MemoryUtil.memAllocFloat(1);
    private static final ByteBuffer bool1 = MemoryUtil.memAlloc(1);

    public static void init() {
        skyPipeline = new Pipeline(POSITION, "sky");
        skyTexPipeline = new Pipeline(POSITION_TEX, "sky_tex");
        skyVcolPipeline = new Pipeline(POSITION_COLOR, "sky_vcol");

        skyPlaneBuffer = MemoryUtil.memAlloc(SKY_PLANE_VERTS * 12);
        skyPlane2Buffer = MemoryUtil.memAlloc(SKY_PLANE_VERTS * 12);
        starBuffer = buildStars();
        sunBuffer = buildSun();
        moonBuffer = MemoryUtil.memAlloc(4 * POSITION_TEX.getSize());
        fanBuffer = MemoryUtil.memAlloc(18 * POSITION_COLOR.getSize());
        belowHorizonBuffer = MemoryUtil.memAlloc(20 * POSITION.getSize());
        endSkyBuffer = buildEndSkyFace();
    }

    /** Called at the head of {@code RenderGlobal.renderSky}, before the GL sky runs. */
    /**
     * @return true when the Vulkan sky fully replaced the vanilla GL sky for this pass,
     *         so the caller can cancel {@code RenderGlobal.renderSky}; false when the GL
     *         path has to run (custom sky renderer, inactive renderer, load failure).
     */
    public static boolean render(float partialTicks, int pass) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null || !Drawer.isActive() || Drawer.skipRendering) {
            return false;
        }

        WorldProvider provider = world.provider;
        // Custom (modded) sky renderers draw arbitrary GL geometry that cannot be
        // replayed generically, so they keep the GL path. The End has a fixed cube sky
        // (renderEndSky); the Nether renders no sky at all (the fog-coloured clear is
        // already correct there, so its redundant GL fog quad can be skipped too).
        if (provider.getSkyRenderer() != null) {
            return false;
        }
        boolean end = provider.getDimensionType().getId() == 1;
        if (!end && !provider.isSurfaceWorld()) {
            return true;
        }

        // Vanilla's renderSky ends with depthMask(true), which cancelling it skips; the
        // sky's own depthMask(false) would otherwise leak into the terrain. Save the
        // frame's depth/cull state and restore it on the way out (N1), the way the
        // world-border pass does.
        boolean depthTest = VRenderSystem.depthTest;
        boolean depthMask = VRenderSystem.depthMask;
        int depthFun = VRenderSystem.depthFun;
        boolean cull = VRenderSystem.cull;
        try {
            if (end) {
                renderEndSky();
            } else {
                renderSky(mc, world, partialTicks, pass);
            }
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("sky", t);
        } finally {
            // The terrain pass re-snapshots every state it needs, but the blend state
            // and the Sampler0 binding are read as-is at draw time, so restore them.
            Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
            VRenderSystem.depthTest = depthTest;
            VRenderSystem.depthMask = depthMask;
            VRenderSystem.depthFun = depthFun;
            VRenderSystem.cull = cull;
            TextureBridge.restoreAtlasBinding();
        }
        return true;
    }

    private static boolean celestialLogPending;

    private static void renderSky(Minecraft mc, World world, float partialTicks, int pass) {
        Drawer drawer = Drawer.getInstance();

        // --- state mirrored from GL at the head of renderSky ---
        VRenderSystem.updateProjectionMatrix();
        VRenderSystem.updateModelViewMatrix();
        Matrix4f headMV = new Matrix4f(VRenderSystem.getModelViewMatrix4f());
        // Vanilla's modelview at renderSky time is the camera rotation only: the -eye
        // translate happens later, per layer in renderBlockLayer. So the whole sky -
        // the plane at y=16 and the celestial bodies at (0,+-100,0) - floats around
        // the camera. Zero the (re-centered, residual) translation Kirino reports and
        // keep every vertex in that same camera-relative space, exactly like vanilla.
        headMV.set(0, 3, 0.0f).set(1, 3, 0.0f).set(2, 3, 0.0f);

        // This head modelview is the same pure camera rotation vanilla has when it calls
        // RenderHelper.enableStandardItemLighting() just before renderEntities, so it is
        // the right basis to put the entity shading lights into eye space.
        VRenderSystem.setLightDirections(headMV);

        snapshotFog();
        // The live fog mode for the lower plane: vanilla re-enables fog before drawing
        // glSkyList2 after the stars, and :228 below overwrites the state with NONE.
        final int fogState = VRenderSystem.getFogState();

        // Depth from the GL-truth mirror (N1): a glGet* hands back garbage when the
        // context does not answer, and Pipeline then throws "unknown depth function"
        // (R24).
        VRenderSystem.depthMask(VRenderSystem.glDepthMask);
        if (VRenderSystem.glDepthTest) {
            VRenderSystem.enableDepthTest();
        } else {
            VRenderSystem.disableDepthTest();
        }
        VRenderSystem.depthFunc(VRenderSystem.glDepthFun);
        // Vanilla keeps GL_CULL_FACE enabled through all of renderSky (renderWorldPass
        // enables it before the sky). That is what makes the two sky planes one-sided:
        // the y=+16 plane (front face down) covers the upper hemisphere and the y=-16
        // plane (front face up) the lower one, so the zenith reads as the near sky
        // colour rather than the clear colour (see buildSkyPlane). The sun, moon, fan
        // and star quads use vanilla's vertex order and face the camera, so mirroring
        // the live cull state keeps them visible exactly as vanilla does.
        if (boolState(GL11.GL_CULL_FACE)) {
            VRenderSystem.enableCull();
        } else {
            VRenderSystem.disableCull();
        }

        // The sky never writes depth (vanilla sets depthMask(false) before the plane).
        VRenderSystem.depthMask(false);

        // --- sky plane (fogged sky color) ---
        Vec3d sky = world.getSkyColor(mc.getRenderViewEntity(), partialTicks);
        float r = (float) sky.x, g = (float) sky.y, b = (float) sky.z;
        if (pass != 2) {
            float[] desat = desaturate(r, g, b);
            r = desat[0]; g = desat[1]; b = desat[2];
        }
        VRenderSystem.setShaderColor(r, g, b, 1.0f);
        VRenderSystem.setShaderPipeline(skyPipeline);
        Drawer.currentBlendState = Pipeline.NO_BLEND_STATE;
        VRenderSystem.applyModelViewMatrix(headMV);
        VRenderSystem.calculateMVP();
        if (skyPlaneVbo == null) {
            buildSkyPlane();
            skyPlaneVbo = uploadStatic(skyPlaneBuffer, SKY_PLANE_VERTS, POSITION);
        }
        drawStaticQuads(drawer, skyPlaneVbo, SKY_PLANE_VERTS);

        // From here on the GL sky disables fog.
        VRenderSystem.setFogState(VRenderSystem.FOG_NONE);

        // --- sunrise/sunset glow fan ---
        float[] glow = world.provider.calcSunriseSunsetColors(
                world.getCelestialAngle(partialTicks), partialTicks);
        if (glow != null) {
            float cr = glow[0], cg = glow[1], cb = glow[2];
            if (pass != 2) {
                float[] desat = desaturate(cr, cg, cb);
                cr = desat[0]; cg = desat[1]; cb = desat[2];
            }
            // rotate(90, x) * rotate(sin(angle) < 0 ? 180 : 0, z) * rotate(90, z)
            Matrix4f mv = new Matrix4f(headMV)
                    .rotate(HALF_PI, 1.0f, 0.0f, 0.0f)
                    .rotate(MathHelper.sin(world.getCelestialAngleRadians(partialTicks)) < 0.0f ? PI : 0.0f,
                            0.0f, 0.0f, 1.0f)
                    .rotate(HALF_PI, 0.0f, 0.0f, 1.0f);

            buildFan(cr, cg, cb, glow[3], glow[0], glow[1], glow[2]);
            VRenderSystem.setShaderPipeline(skyVcolPipeline);
            Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE; // SRC_ALPHA / ONE_MINUS_SRC_ALPHA
            VRenderSystem.applyModelViewMatrix(mv);
            VRenderSystem.calculateMVP();
            drawer.draw(fanBuffer, 6, POSITION_COLOR, 18);
        }

        // --- sun, moon, stars (one shared rotation block) ---
        // rotate(-90, y) * rotate(celestialAngle * 2PI, x)
        Matrix4f celestial = new Matrix4f(headMV)
                .rotate(-HALF_PI, 0.0f, 1.0f, 0.0f)
                .rotate(world.getCelestialAngle(partialTicks) * (TWO_PI), 1.0f, 0.0f, 0.0f);
        VRenderSystem.applyModelViewMatrix(celestial);
        VRenderSystem.calculateMVP();

        // One-shot forensics: AutoTest sets celestialLogPending right before a sky capture so the
        // screenshot lines up with the predicted sun/moon position. This must stay off otherwise -
        // formatting these matrix dumps every frame caused a GC hitch (micro-freeze) every ~40 frames.
        if (celestialLogPending) {
            celestialLogPending = false;
            java.nio.FloatBuffer mvpBuf = VRenderSystem.getMVP().asFloatBuffer().position(0).limit(16);
            float[] m = new float[16];
            mvpBuf.get(m);
            // Same read path as the shader's UBO: joml's FloatBuffer ctor is column-major.
            Matrix4f mvp = new Matrix4f(VRenderSystem.getMVP().asFloatBuffer().position(0).limit(16));
            Vector4f clip = mvp.transform(new Vector4f(0.0f, 100.0f, 0.0f, 1.0f));
            Vector4f moonClip = mvp.transform(new Vector4f(0.0f, -100.0f, 0.0f, 1.0f));
            Initializer.LOGGER.info(
                    "[sky] sunClip=({},{},{},{}) ndc=({},{}) moonNdc=({},{}) w={} angle={}",
                    clip.x, clip.y, clip.z, clip.w, clip.x / clip.w, clip.y / clip.w,
                    moonClip.x / moonClip.w, moonClip.y / moonClip.w, moonClip.w,
                    world.getCelestialAngle(partialTicks));
            Initializer.LOGGER.info("[sky] rawMVP={}", raws(m));
        }

        float rainFade = 1.0f - world.getRainStrength(partialTicks);

        if (ensureCelestialTextures()) {
            VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, rainFade);
            VRenderSystem.setShaderPipeline(skyTexPipeline);
            // GL_SRC_ALPHA, GL_ONE: additive, like the GL sky
            Drawer.currentBlendState = ADDITIVE_BLEND;

            VTextureSelector.bindTexture(sunTexture);
            drawer.draw(sunBuffer, 7, POSITION_TEX, 4);

            buildMoon(world.getMoonPhase());
            VTextureSelector.bindTexture(moonTexture);
            drawer.draw(moonBuffer, 7, POSITION_TEX, 4);
        }

        float starBrightness = world.getStarBrightness(partialTicks) * rainFade;
        if (starBrightness > 0.0f) {
            VRenderSystem.setShaderColor(starBrightness, starBrightness, starBrightness, starBrightness);
            VRenderSystem.setShaderPipeline(skyPipeline);
            if (starsVbo == null) {
                starsVbo = uploadStatic(starBuffer, starVertexCount, POSITION);
            }
            drawStaticQuads(drawer, starsVbo, starVertexCount);
        }

        // --- lower sky plane (vanilla glSkyList2, y=-16) ---
        // Vanilla draws it here - after the stars - with fog re-enabled and blend
        // disabled, so the opaque plane overwrites any star that bled below the horizon
        // instead of adding to it (L7). Drawing both planes up front made additive
        // stars visible through the lower sky.
        VRenderSystem.setFogState(fogState);
        VRenderSystem.setShaderPipeline(skyPipeline);
        Drawer.currentBlendState = Pipeline.NO_BLEND_STATE;
        if (skyPlane2Vbo == null) {
            buildSkyPlane();
            skyPlane2Vbo = uploadStatic(skyPlane2Buffer, SKY_PLANE_VERTS, POSITION);
        }
        // Vanilla: d3 = eyeY - world.getHorizon(). The plane buffer sits at y=-16 and
        // rides translate(0, -(d3 - 16), 0), so its edge lands exactly on the world
        // horizon at any altitude; below the horizon (d3 < 0, e.g. a cave under the
        // floor) vanilla first draws the plane at +12 and a black box around the eye
        // down to -(d3 + 65), then the coloured plane (L7). The port used to draw it
        // at a fixed -16 under the eye with the plain sky colour.
        double horizonDelta = mc.player != null
                ? mc.player.getPositionEyes(partialTicks).y - world.getHorizon() : 16.0;
        if (horizonDelta < 0.0) {
            VRenderSystem.setShaderColor(0.0f, 0.0f, 0.0f, 1.0f);
            VRenderSystem.applyModelViewMatrix(SCRATCH_HEAD.set(headMV).translate(0.0f, 12.0f, 0.0f));
            VRenderSystem.calculateMVP();
            drawStaticQuads(drawer, skyPlane2Vbo, SKY_PLANE_VERTS);
            VRenderSystem.applyModelViewMatrix(headMV);
            VRenderSystem.calculateMVP();
            buildBelowHorizonBox(-(float) (horizonDelta + 65.0));
            drawer.draw(belowHorizonBuffer, 7, POSITION, 20);
        }
        if (world.provider.isSkyColored()) {
            VRenderSystem.setShaderColor(r * 0.2f + 0.04f, g * 0.2f + 0.04f, b * 0.6f + 0.1f, 1.0f);
        } else {
            VRenderSystem.setShaderColor(r, g, b, 1.0f);
        }
        VRenderSystem.applyModelViewMatrix(
                SCRATCH_HEAD.set(headMV).translate(0.0f, -(float) (horizonDelta - 16.0), 0.0f));
        VRenderSystem.calculateMVP();
        drawStaticQuads(drawer, skyPlane2Vbo, SKY_PLANE_VERTS);
    }

    /** Vanilla's below-horizon box, exact vertex order (cull-compatible, L7). */
    private static void buildBelowHorizonBox(float bottom) {
        ByteBuffer buf = belowHorizonBuffer;
        buf.position(0);
        putPos(buf, -1.0f, bottom, 1.0f);  putPos(buf, 1.0f, bottom, 1.0f);
        putPos(buf, 1.0f, -1.0f, 1.0f);    putPos(buf, -1.0f, -1.0f, 1.0f);
        putPos(buf, -1.0f, -1.0f, -1.0f);  putPos(buf, 1.0f, -1.0f, -1.0f);
        putPos(buf, 1.0f, bottom, -1.0f);  putPos(buf, -1.0f, bottom, -1.0f);
        putPos(buf, 1.0f, -1.0f, -1.0f);   putPos(buf, 1.0f, -1.0f, 1.0f);
        putPos(buf, 1.0f, bottom, 1.0f);   putPos(buf, 1.0f, bottom, -1.0f);
        putPos(buf, -1.0f, bottom, -1.0f); putPos(buf, -1.0f, bottom, 1.0f);
        putPos(buf, -1.0f, -1.0f, 1.0f);   putPos(buf, -1.0f, -1.0f, -1.0f);
        putPos(buf, -1.0f, -1.0f, -1.0f);  putPos(buf, -1.0f, -1.0f, 1.0f);
        putPos(buf, 1.0f, -1.0f, 1.0f);    putPos(buf, 1.0f, -1.0f, -1.0f);
        buf.position(0);
    }

    /**
     * The End sky: vanilla {@code RenderGlobal.renderSkyEnd} draws the inside of a
     * 200-block cube of {@code end_sky.png} - one dark (40,40,40) square at y=-100 with
     * UVs 0..16 (GL_REPEAT), drawn six times under fixed rotations. The square is built
     * once; the rotations ride on the modelview on top of the camera-only head matrix,
     * exactly like the overworld sky layers.
     */
    private static void renderEndSky() {
        VRenderSystem.updateProjectionMatrix();
        VRenderSystem.updateModelViewMatrix();
        Matrix4f headMV = new Matrix4f(VRenderSystem.getModelViewMatrix4f());
        headMV.set(0, 3, 0.0f).set(1, 3, 0.0f).set(2, 3, 0.0f);

        // renderSkyEnd disables fog and never writes depth; cull mirrors the live GL
        // state like the overworld sky does (the cube is seen from the inside).
        VRenderSystem.setFogState(VRenderSystem.FOG_NONE);
        VRenderSystem.depthMask(false);
        if (boolState(GL11.GL_CULL_FACE)) {
            VRenderSystem.enableCull();
        } else {
            VRenderSystem.disableCull();
        }

        if (endSkyTexture == null) {
            if (endSkyTextureFailed) {
                return;
            }
            try {
                // blur=false, clamp=false: NEAREST + GL_REPEAT, the vanilla SimpleTexture
                // defaults the UVs 0..16 rely on.
                endSkyTexture = loadTexture("textures/environment/end_sky.png", false);
            } catch (Exception e) {
                endSkyTextureFailed = true;
                Initializer.LOGGER.error("[sky] cannot load end_sky texture", e);
                return;
            }
        }

        VRenderSystem.setShaderColor(40.0f / 255.0f, 40.0f / 255.0f, 40.0f / 255.0f, 1.0f);
        VRenderSystem.setShaderPipeline(skyTexPipeline);
        Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
        VTextureSelector.bindTexture(endSkyTexture);

        Drawer drawer = Drawer.getInstance();
        for (int face = 0; face < 6; ++face) {
            Matrix4f mv = new Matrix4f(headMV);
            switch (face) {
                case 1 -> mv.rotate(HALF_PI, 1.0f, 0.0f, 0.0f);
                case 2 -> mv.rotate(-HALF_PI, 1.0f, 0.0f, 0.0f);
                case 3 -> mv.rotate(PI, 1.0f, 0.0f, 0.0f);
                case 4 -> mv.rotate(HALF_PI, 0.0f, 0.0f, 1.0f);
                case 5 -> mv.rotate(-HALF_PI, 0.0f, 0.0f, 1.0f);
                default -> {}
            }
            VRenderSystem.applyModelViewMatrix(mv);
            VRenderSystem.calculateMVP();
            if (endSkyVbo == null) {
                endSkyVbo = uploadStatic(endSkyBuffer, 4, POSITION_TEX);
            }
            drawStaticQuads(drawer, endSkyVbo, 4);
        }
    }

    private static boolean endSkyTextureFailed;

    /** The End sky cube face: the y=-100 square, UVs 0..16, like vanilla's loop body. */
    private static ByteBuffer buildEndSkyFace() {
        ByteBuffer buf = MemoryUtil.memAlloc(4 * POSITION_TEX.getSize());
        putPosUv(buf, -100.0f, -100.0f, -100.0f, 0.0f, 0.0f);
        putPosUv(buf, -100.0f, -100.0f, 100.0f, 0.0f, 16.0f);
        putPosUv(buf, 100.0f, -100.0f, 100.0f, 16.0f, 16.0f);
        putPosUv(buf, 100.0f, -100.0f, -100.0f, 16.0f, 0.0f);
        buf.position(0);
        return buf;
    }

    /**
     * Requests a precise one-shot dump of the sun/moon clip positions from inside the
     * next renderSky frame (where the live GL matrices are the world's). AutoTest sets
     * this right before each sky capture so the screenshot matches the prediction.
     */
    public static void logCelestial(Minecraft mc, float partialTicks) {
        celestialLogPending = true;
    }

    // --- geometry builders (replicating the vanilla generators) ---

    /** Uploads constant CPU geometry into a fresh device-local VBO (M12, called once). */
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

    /**
     * Refills the two sky planes (vanilla glSkyList + glSkyList2: 13x13 grids from -384
     * to 384). Vanilla draws them at opposite ends of renderSky - the y=+16 plane first
     * with fog, the y=-16 plane after the stars with fog re-enabled and blend disabled
     * (see renderSky) - so they are separate buffers drawn at those two points. Vanilla
     * builds them with a rotation-only modelview (the -eye translate happens later, per
     * block layer), so the grids float around the camera. The head modelview here is
     * likewise rotation-only, so the vertices stay in raw camera-relative space - no eye
     * subtraction.
     *
     * Both grids use vanilla's exact vertex order, so their front faces point the same
     * way and GL_CULL_FACE (enabled through all of renderSky) culls them identically:
     * {@code glSkyList} is the y=+16 plane with its front face DOWN, visible from below
     * so it covers the upper hemisphere (the zenith reads as the near, unfogged sky
     * colour, not the clear colour); {@code glSkyList2} is the y=-16 plane with its front
     * face UP, visible from above to cover the lower hemisphere (mostly hidden by
     * terrain, fogged to the fog colour at the horizon). The geometry is constant - the
     * camera rotation rides the modelview matrix - so each is built once and kept in a
     * persistent VBO (see skyPlaneVbo / skyPlane2Vbo).
     */
    private static void buildSkyPlane() {
        ByteBuffer buf = skyPlaneBuffer;
        buf.position(0);
        for (int k = -384; k <= 384; k += 64) {
            for (int l = -384; l <= 384; l += 64) {
                float f = k;
                float f1 = k + 64;
                // glSkyList: renderSky(16, false) - front face down, seen looking up.
                putPos(buf, f, 16.0f, l);
                putPos(buf, f1, 16.0f, l);
                putPos(buf, f1, 16.0f, l + 64.0f);
                putPos(buf, f, 16.0f, l + 64.0f);
            }
        }
        buf.position(0);

        buf = skyPlane2Buffer;
        buf.position(0);
        for (int k = -384; k <= 384; k += 64) {
            for (int l = -384; l <= 384; l += 64) {
                float f = k;
                float f1 = k + 64;
                // glSkyList2: renderSky(-16, true) - the f/f1 swap reverses the winding,
                // front face up, seen looking down.
                putPos(buf, f1, -16.0f, l);
                putPos(buf, f, -16.0f, l);
                putPos(buf, f, -16.0f, l + 64.0f);
                putPos(buf, f1, -16.0f, l + 64.0f);
            }
        }
        buf.position(0);
    }

    /** {@code RenderGlobal.renderStars}: 1500 deterministic star quads. */
    private static ByteBuffer buildStars() {
        ByteBuffer buf = MemoryUtil.memAlloc(1500 * 4 * 12);
        Random random = new Random(10842L);
        int verts = 0;

        for (int i = 0; i < 1500; ++i) {
            double d0 = random.nextFloat() * 2.0F - 1.0F;
            double d1 = random.nextFloat() * 2.0F - 1.0F;
            double d2 = random.nextFloat() * 2.0F - 1.0F;
            double d3 = 0.15F + random.nextFloat() * 0.1F;
            double d4 = d0 * d0 + d1 * d1 + d2 * d2;
            if (d4 < 1.0D && d4 > 0.01D) {
                d4 = 1.0D / Math.sqrt(d4);
                d0 *= d4;
                d1 *= d4;
                d2 *= d4;
                double d5 = d0 * 100.0D;
                double d6 = d1 * 100.0D;
                double d7 = d2 * 100.0D;
                double d8 = Math.atan2(d0, d2);
                double d9 = Math.sin(d8);
                double d10 = Math.cos(d8);
                double d11 = Math.atan2(Math.sqrt(d0 * d0 + d2 * d2), d1);
                double d12 = Math.sin(d11);
                double d13 = Math.cos(d11);
                double d14 = random.nextDouble() * Math.PI * 2.0D;
                double d15 = Math.sin(d14);
                double d16 = Math.cos(d14);

                for (int j = 0; j < 4; ++j) {
                    double d18 = ((j & 2) - 1) * d3;
                    double d19 = ((j + 1 & 2) - 1) * d3;
                    double d21 = d18 * d16 - d19 * d15;
                    double d22 = d19 * d16 + d18 * d15;
                    double d24 = d21 * d12;
                    double d25 = -d21 * d13;
                    double d26 = d25 * d9 - d22 * d10;
                    double d28 = d22 * d9 + d25 * d10;
                    buf.putFloat((float) (d5 + d26));
                    buf.putFloat((float) (d6 + d24));
                    buf.putFloat((float) (d7 + d28));
                    ++verts;
                }
            }
        }

        starVertexCount = verts;
        buf.position(0);
        return buf;
    }

    /** The sun quad: 30x30 at y=100, full texture. */
    private static ByteBuffer buildSun() {
        ByteBuffer buf = MemoryUtil.memAlloc(4 * 20);
        putPosUv(buf, -30.0f, 100.0f, -30.0f, 0.0f, 0.0f);
        putPosUv(buf, 30.0f, 100.0f, -30.0f, 1.0f, 0.0f);
        putPosUv(buf, 30.0f, 100.0f, 30.0f, 1.0f, 1.0f);
        putPosUv(buf, -30.0f, 100.0f, 30.0f, 0.0f, 1.0f);
        buf.position(0);
        return buf;
    }

    /** The moon quad: 20x20 at y=-100, UVs picking the current phase cell. */
    private static void buildMoon(int moonPhase) {
        if (moonPhase == builtMoonPhase) {
            return;
        }
        builtMoonPhase = moonPhase;

        int k = moonPhase % 4;
        int l = moonPhase / 4 % 2;
        float u0 = k / 4.0f, v0 = l / 2.0f;
        float u1 = (k + 1) / 4.0f, v1 = (l + 1) / 2.0f;

        moonBuffer.position(0);
        putPosUv(moonBuffer, -20.0f, -100.0f, 20.0f, u1, v1);
        putPosUv(moonBuffer, 20.0f, -100.0f, 20.0f, u0, v1);
        putPosUv(moonBuffer, 20.0f, -100.0f, -20.0f, u0, v0);
        putPosUv(moonBuffer, -20.0f, -100.0f, -20.0f, u1, v0);
        moonBuffer.position(0);
    }

    /** The glow fan: center at (0,100,0) plus 17 rim vertices, rebuilt every frame. */
    private static void buildFan(float cr, float cg, float cb, float ca,
                                 float rimR, float rimG, float rimB) {
        fanBuffer.position(0);
        putPosColor(fanBuffer, 0.0f, 100.0f, 0.0f, cr, cg, cb, ca);
        for (int l = 0; l <= 16; ++l) {
            float angle = l * (float) (Math.PI * 2.0) / 16.0f;
            float sin = MathHelper.sin(angle);
            float cos = MathHelper.cos(angle);
            // The rim keeps the raw (non-desaturated) glow color, like vanilla.
            putPosColor(fanBuffer, sin * 120.0f, cos * 120.0f, -cos * 40.0f * ca,
                    rimR, rimG, rimB, 0.0f);
        }
        fanBuffer.position(0);
    }

    // --- textures ---

    private static boolean texturesFailed;

    private static boolean ensureCelestialTextures() {
        if (sunTexture != null && moonTexture != null) {
            return true;
        }
        if (texturesFailed) {
            return false;
        }
        try {
            if (sunTexture == null) {
                sunTexture = loadTexture("textures/environment/sun.png", true);
            }
            if (moonTexture == null) {
                moonTexture = loadTexture("textures/environment/moon_phases.png", true);
            }
            Initializer.LOGGER.info("[sky] celestial textures uploaded");
            return true;
        } catch (Exception e) {
            texturesFailed = true;
            Initializer.LOGGER.error("[sky] cannot load sun/moon textures", e);
            return false;
        }
    }

    /**
     * Uploads a PNG straight from the resource manager (no GL round-trip). The row
     * order matches the GL texture: Minecraft uploads the BufferedImage rows as-is,
     * so row 0 of both textures is the top of the image and the UVs transfer 1:1.
     */
    private static VulkanImage loadTexture(String path, boolean clamp) throws Exception {
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

        // NEAREST filtering (blur=false), clamp as requested; vanilla uploads environment
        // textures via SimpleTexture without mcmeta: no blur, no mipmaps.
        VulkanImage texture = new VulkanImage(1, width, height, 4, false, clamp);
        texture.uploadSubTextureAsync(0, width, height, 0, 0, 4, 0, 0, width, pixels);
        MemoryUtil.memFree(pixels);
        return texture;
    }

    // --- helpers ---

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

    private static String raws(float[] values) {
        StringBuilder sb = new StringBuilder();
        for (float v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.4f", v));
        }
        return sb.toString();
    }

    private static void putPos(ByteBuffer buf, float x, float y, float z) {
        buf.putFloat(x).putFloat(y).putFloat(z);
    }

    private static void putPosUv(ByteBuffer buf, float x, float y, float z, float u, float v) {
        buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
    }

    private static void putPosColor(ByteBuffer buf, float x, float y, float z,
                                    float r, float g, float b, float a) {
        buf.putFloat(x).putFloat(y).putFloat(z);
        buf.put((byte) (clamp01(r) * 255.0f));
        buf.put((byte) (clamp01(g) * 255.0f));
        buf.put((byte) (clamp01(b) * 255.0f));
        buf.put((byte) (clamp01(a) * 255.0f));
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private SkyRenderer() {
    }
}
