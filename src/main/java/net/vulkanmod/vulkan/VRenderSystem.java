package net.vulkanmod.vulkan;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Vulkan-side replacement for the OpenGL render state. Mirrors the state the game
 * sets through {@code GlStateManager} and the GL matrix stack into plain data that
 * the Vulkan pipelines consume as UBOs/push constants.
 *
 * <p>Ported from VulkanMod (1.18.2 branch), where this class mirrored blaze3d's
 * {@code RenderSystem}; on 1.12.2 the state is fed into it by mixins on
 * {@code GlStateManager} and friends.</p>
 */
public class VRenderSystem {

    private static String capsString = "";
    private static String cpuInfo;

    private static long window;

    // --- Depth / mask / cull state (GL defaults) ---
    public static boolean depthTest = true;
    public static boolean depthMask = true;
    public static int depthFun = 515; // GL_LEQUAL (515; the GL context default is GL_LESS = 513)

    public static int colorMask = Pipeline.ColorMask.getColorMask(true, true, true, true);

    public static boolean cull = true;

    // --- GL-truth mirror of the depth/cull state, written ONLY by the GlStateManager
    // hooks (GlStateManagerDepthM), i.e. only by vanilla calls. The live fields above
    // double as the per-pass override channel (sky/clouds/GUI/world-border write them
    // directly), so a pass can always recover the state vanilla actually left behind.
    // The terrain's first layer re-syncs the live fields from these as a safety net.
    // The depth-func default is GL_LEQUAL (515), not the GL context default GL_LESS:
    // vanilla's Framebuffer.bindFramebuffer sets LEQUAL before every world frame.
    public static boolean glDepthTest = true;
    public static boolean glDepthMask = true;
    public static int glDepthFun = 515; // GL_LEQUAL
    public static boolean glCull = true;

    // Input-assembly topology of the pipeline variant selected at draw time. GL's
    // immediate modes map onto it 1:1; the default is the triangle list every world/GUI
    // pass uses. The debug-line capture switches it to a line topology for its draws.
    public static int topology = 3; // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST

    // GL default: the fixed-function alpha test is enabled (GREATER 0.1)
    public static boolean alphaTest = true;
    public static int alphaTestFunc = 516; // GL_GREATER
    public static float alphaTestRef = 0.1f;

    // --- Current GL colour mirrored from GlStateManager.color (Kirino GL does not
    // answer glGetFloatv(GL_CURRENT_COLOR)); consumed by the GUI pass ---
    public static float trackedColorR = 1, trackedColorG = 1, trackedColorB = 1, trackedColorA = 1;

    // --- GL texture id bound to GL_TEXTURE_2D, mirrored from GlStateManager.bindTexture
    // (the draw-time bind paths all route through it); consumed by the entity/GUI texture
    // mirrors instead of a glGetInteger(GL_TEXTURE_BINDING_2D) per draw ---
    public static int trackedBoundTexture = 0;

    // --- Fixed-function texturing enable bit mirrored from GlStateManager.enableTexture2D/
    // disableTexture2D (GL default: enabled). Some GUI rectangles (GuiSlot's scrollbar and
    // its top/bottom fades) are drawn with texturing OFF so that only the vertex colour
    // shows; the Vulkan pipelines always sample, so those draws bind a white image instead
    // to reproduce "colour only". ---
    public static boolean textureEnabled = true;

    // --- Blend state (GL defaults: disabled, ONE, ZERO) ---
    public static boolean blendEnabled = false;
    public static int blendSrcRgb = 1;   // GL_ONE
    public static int blendDstRgb = 0;   // GL_ZERO
    public static int blendSrcAlpha = 1; // GL_ONE
    public static int blendDstAlpha = 0; // GL_ZERO

    public static boolean logicOpEnabled = false;
    public static int logicOpMode = 0x1503; // GL_COPY, the GL default

    public static float clearDepth = 1.0f;
    public static FloatBuffer clearColor = MemoryUtil.memAllocFloat(4);

    static {
        clearColor.put(0, 0.1f).put(1, 0.2f).put(2, 0.5f).put(3, 1.0f);
    }

    /**
     * The frame clear color, fed by {@code EntityRendererM} from the fog color
     * {@code EntityRenderer.updateFogColor} computes (the same values vanilla passes
     * to {@code GlStateManager.clearColor}), so the void and the uncovered zenith
     * match the fog exactly like in GL.
     */
    public static void setClearColor(float r, float g, float b) {
        clearColor.position(0);
        clearColor.put(0, r).put(1, g).put(2, b).put(3, 1.0f);
        clearColor.position(0);
    }

    public static ByteBuffer modelViewMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer projectionMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer TextureMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer MVP = MemoryUtil.memAlloc(16 * 4);

    // Cached FloatBuffer views of the matrix mirrors: asFloatBuffer() allocates a fresh
    // view per call, which the JFR profile showed as a per-draw DirectFloatBufferU
    // allocation (updateModelViewMatrix runs once per entity part and chunk draw).
    // Users must position(0)/limit(16) before reading, as before.
    private static final FloatBuffer modelViewMatrixF = modelViewMatrix.asFloatBuffer();
    private static final FloatBuffer projectionMatrixF = projectionMatrix.asFloatBuffer();

    public static ByteBuffer ChunkOffset = MemoryUtil.memAlloc(3 * 4);
    public static ByteBuffer lightDirection0 = MemoryUtil.memAlloc(3 * 4);
    public static ByteBuffer lightDirection1 = MemoryUtil.memAlloc(3 * 4);

    public static float[] shaderColor = {1.0f, 1.0f, 1.0f, 1.0f};
    public static float[] shaderFogColor = {0.7529412f, 0.8117647f, 0.8901961f, 1.0f};

    // --- Fog state ---
    public static final int FOG_NONE = 0;
    public static final int FOG_LINEAR = 1;
    public static final int FOG_EXP = 2;
    public static final int FOG_EXP2 = 3;

    private static int fogState = FOG_LINEAR;

    /**
     * GL_FOG_DISTANCE_MODE_NV (0x855A) value mirrored from
     * {@code GlStateManager.glFogi} (R16). Vanilla's {@code setupFog} sets it to
     * GL_EYE_RADIAL_NV (0x855B) whenever the GL_NV_fog_distance extension is
     * present and never resets it, so the default is GL_EYE_PLANE_ABSOLUTE_NV
     * (0x855C), the |z| metric the drivers without the extension use. Fed to the
     * fog shaders as the {@code FogRadial} UBO field; deliberately not reset in
     * {@link #resetState()}, matching the sticky GL state it mirrors.
     */
    private static int fogDistanceMode = 0x855C;

    private static float fogStart = 0.95f;
    private static float fogEnd = 1.0f;
    private static float fogDensity = 0.1f;
    private static boolean fogEnabled = true;

    private static float lineWidth = 1.0f;

    /**
     * The widest line the device accepts for vkCmdSetLineWidth (M3): limits
     * lineWidthRange[1] when the wideLines feature is enabled, 1.0 otherwise.
     * Written once at device creation by {@code Vulkan.createLogicalDevice}.
     */
    private static float maxLineWidth = 1.0f;
    /**
     * The narrowest one: lineWidthRange[0] with wideLines, 1.0 otherwise - without the
     * feature the width must be exactly 1.0 (VUID-vkCmdSetLineWidth-lineWidth-00788, M3).
     */
    private static float minLineWidth = 1.0f;
    private static float pointSize = 1.0f;
    private static double gameTime = 0.0;

    private static Pipeline currentPipeline;

    private static final float[] depthBias = new float[2];

    public static void initRenderer() {
        try {
            cpuInfo = String.format("%dx %s", Runtime.getRuntime().availableProcessors(),
                    System.getProperty("os.arch", "unknown")).replaceAll("\\s+", " ");
        } catch (Throwable throwable) {
        }

        Vulkan.initVulkan(window);
    }

    public static String getCapsString() {
        return capsString;
    }

    public static String getProcessorInfo() {
        return cpuInfo == null ? "Unknown" : cpuInfo;
    }

    public static ByteBuffer getChunkOffset() { return ChunkOffset; }

    public static int maxSupportedTextureSize() {
        return Vulkan.deviceProperties.limits().maxImageDimension2D();
    }

    // --- Matrices ---

    /**
     * Reads the current GL modelview matrix into the mirror buffer and recomputes MVP.
     * On 1.12.2 the GL matrix stack still receives every transform, so querying it at
     * draw time is exact and needs no shadowing.
     */
    public static void updateModelViewMatrix() {
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX,
                modelViewMatrixF.position(0).limit(16));
        calculateMVP();
    }

    /**
     * Replaces the depth row of the mirrored GL projection with a perspective one for
     * [near, far] (F3). Clouds use vanilla's farPlane*4 projection; with the main camera's
     * depth range their depth matches the rest of the scene, so motion vectors rebuilt
     * from depth and FSR's depth inputs stay consistent. x, y and w are untouched.
     */
    public static void overrideProjectionDepth(float near, float far) {
        // Column-major: m22 at 10, m32 at 14.
        projectionMatrixF.put(10, -(far + near) / (far - near));
        projectionMatrixF.put(14, -2.0f * far * near / (far - near));
    }

    public static void updateProjectionMatrix() {
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX,
                projectionMatrixF.position(0).limit(16));
    }

    /**
     * Bumped by the GlStateManager matrix-method hooks (GlStateManagerMatrixM). The GUI
     * pass caches the projection matrix on it and batches font glyphs with it: while the
     * counter holds, no matrix op ran, so a matrix read earlier is still current. Vanilla
     * GUI code drives every matrix op through GlStateManager; a mod mutating the matrix
     * stack through raw GL11 inside a GuiScreen would slip past the counter.
     */
    private static long matrixVersion;

    public static long getMatrixVersion() {
        return matrixVersion;
    }

    public static void bumpMatrixVersion() {
        ++matrixVersion;
    }

    public static void applyMVP(Matrix4f MV, Matrix4f P) {
        applyModelViewMatrix(MV);
        applyProjectionMatrix(P);
        calculateMVP();
    }

    public static void applyModelViewMatrix(Matrix4f mat) {
        modelViewMatrix.position(0);
        mat.get(modelViewMatrix);
    }

    public static void applyProjectionMatrix(Matrix4f mat) {
        projectionMatrix.position(0);
        mat.get(projectionMatrix);
    }

    // Scratch matrices for calculateMVP (client-thread only) - avoids ~4 allocations per
    // chunk draw, which was measurable GC pressure at hundreds of chunks per frame.
    private static final org.joml.Matrix4f SCRATCH_MV = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f SCRATCH_P = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f SCRATCH_T1 = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f SCRATCH_T2 = new org.joml.Matrix4f();

    // GL projections map depth to [-1, 1]; Vulkan's clip volume is [0, w].
    // Remap z' = 0.5z + 0.5w before multiplying (also fixes near-plane clipping).
    // NOTE: joml's ctor takes column-major args (m00, m01, m02, m03, m10, ...),
    // so the 0.5 that adds w into z belongs in column 3, not column 2.
    private static final org.joml.Matrix4f DEPTH_REMAP = new org.joml.Matrix4f(
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 0.5f, 0f,
            0, 0, 0.5f, 1f);

    /** DEPTH_REMAP plus the scene-phase jitter translation (B7). */
    private static final org.joml.Matrix4f CLIP_REMAP = new org.joml.Matrix4f(DEPTH_REMAP);

    private static org.joml.Matrix4f clipRemap() {
        net.vulkanmod.render.CameraHistory.applyJitter(CLIP_REMAP);
        return CLIP_REMAP;
    }

    public static void calculateMVP() {
        SCRATCH_MV.set(modelViewMatrixF.position(0).limit(16));
        SCRATCH_P.set(projectionMatrixF.position(0).limit(16));

        clipRemap().mul(SCRATCH_P, SCRATCH_T1).mul(SCRATCH_MV, SCRATCH_T2);

        MVP.position(0);
        SCRATCH_T2.get(MVP);
    }

    /**
     * Captures the camera model-view and the combined depth-remapped projection at the head of
     * a chunk layer pass (before any per-section translate). The batched path then derives each
     * section's matrices from these plus its origin, avoiding a GL matrix query per section.
     */
    public static void captureCameraMatrices(org.joml.Matrix4f outModelView, org.joml.Matrix4f outProjRemap) {
        updateProjectionMatrix();
        updateModelViewMatrix();
        outModelView.set(modelViewMatrixF.position(0).limit(16));
        SCRATCH_P.set(projectionMatrixF.position(0).limit(16));
        // History keeps the unjittered camera; draws get the jittered one.
        DEPTH_REMAP.mul(SCRATCH_P, SCRATCH_T1);
        net.vulkanmod.render.CameraHistory.captureMain(outModelView, SCRATCH_T1, SCRATCH_P);
        clipRemap().mul(SCRATCH_P, outProjRemap);
    }

    /**
     * Builds one section's model-view and MVP from the captured camera matrices and the
     * section's camera-relative offset, writing them into {@link #modelViewMatrix} / {@link #MVP}.
     * Equivalent to vanilla's {@code translate(origin - camera)} applied over the camera matrix
     * (the negligible scale-about-8 z-fighting nudge is omitted).
     */
    public static void applySectionModelView(org.joml.Matrix4f cameraModelView, org.joml.Matrix4f projRemap,
                                             float dx, float dy, float dz) {
        SCRATCH_MV.set(cameraModelView).translate(dx, dy, dz);
        modelViewMatrix.position(0);
        SCRATCH_MV.get(modelViewMatrix);

        projRemap.mul(SCRATCH_MV, SCRATCH_T2);
        MVP.position(0);
        SCRATCH_T2.get(MVP);
    }

    /*
     * The two directional lights of RenderHelper.enableStandardItemLighting(): diffuse
     * 0.6 each, no specular, on top of a 0.4 light-model ambient. Vanilla positions them
     * with the modelview at the pure camera rotation (EntityRenderer.renderWorldPass pops
     * the -eye translate before calling it), so they are fixed in world space and appear
     * in eye space as cameraRot * direction.
     */
    private static final org.joml.Vector3f LIGHT0_DIR = new org.joml.Vector3f(0.2f, 1.0f, -0.7f).normalize();
    private static final org.joml.Vector3f LIGHT1_DIR = new org.joml.Vector3f(-0.2f, 1.0f, 0.7f).normalize();

    /**
     * Mirrors {@code glLight(light, GL_POSITION, params)} into the shader light buffers.
     * GL stores the light direction transformed by the modelview current at call time,
     * and Kirino tracks the matrix stack, so querying it here is exact. The GUI item
     * pass (enableGUIStandardItemLighting rotates -30 deg first) and the first-person
     * hand (rotateX/rotateY applied) position the lights with their own transforms on
     * the stack, where the camera-rotation default would shade them wrong.
     */
    public static void mirrorLightPosition(int light, java.nio.FloatBuffer params) {
        org.joml.Vector3f dir = new org.joml.Vector3f(params.get(0), params.get(1), params.get(2));
        if (params.get(3) != 0.0f || dir.lengthSquared() == 0.0f) {
            return; // positional or zero light: the item paths only use directional ones
        }
        updateModelViewMatrix();
        LIGHT_SCRATCH_MV.set(modelViewMatrixF.position(0).limit(16));
        LIGHT_SCRATCH_MV.transformDirection(dir.normalize());
        writeDirection(light == 16384 ? lightDirection0 : lightDirection1, dir);
    }

    private static final Matrix4f LIGHT_SCRATCH_MV = new Matrix4f();

    /** Stores the entity shading lights in eye space for {@code Light0/1_Direction}. */
    public static void setLightDirections(Matrix4f cameraRot) {
        org.joml.Vector3f light0 = new org.joml.Vector3f(LIGHT0_DIR);
        org.joml.Vector3f light1 = new org.joml.Vector3f(LIGHT1_DIR);

        writeDirection(lightDirection0, cameraRot.transformDirection(light0));
        writeDirection(lightDirection1, cameraRot.transformDirection(light1));
    }

    private static void writeDirection(ByteBuffer buffer, org.joml.Vector3f v) {
        buffer.position(0);
        buffer.putFloat(v.x).putFloat(v.y).putFloat(v.z);
        // Vec3f.update() memCopies src.remaining(), so leave it ready to read.
        buffer.position(0);
    }

    /**
     * Mirror of the GL texture matrix, per texture unit. Kirino tracks the texture stacks
     * internally but does not expose them through {@code glGetFloatv(GL_TEXTURE_MATRIX)},
     * so the port keeps its own copies. Two vanilla users matter: the enchantment glint
     * scrolls its UVs on unit 0 (scale + translate + rotate), while
     * {@code EntityRenderer.enableLightmap} leaves a scale/translate on the lightmap
     * unit's matrix - conflating the units would smear that transform over the atlas UVs
     * of every item icon. The {@code GlStateManager} matrix hooks feed the current unit;
     * only calls made while the texture mode is current apply.
     */
    private static final class TexUnit {
        final Matrix4f cur = new Matrix4f();
        final java.util.ArrayDeque<Matrix4f> stack = new java.util.ArrayDeque<>();
    }

    private static final TexUnit[] TEX_UNITS = new TexUnit[16];
    private static int texUnit;
    private static int matrixMode = 5888; // GL_MODELVIEW

    /** The active texture unit tracked from {@code GlStateManager.setActiveTexture}. */
    public static int activeTexUnit() {
        return texUnit;
    }

    private static TexUnit texUnit(int i) {
        TexUnit u = TEX_UNITS[i];
        if (u == null) {
            u = new TexUnit();
            TEX_UNITS[i] = u;
        }
        return u;
    }

    public static void texMatrixActiveTexture(int textureEnum) {
        int unit = textureEnum - 33984; // GL_TEXTURE0
        if (unit >= 0 && unit < TEX_UNITS.length) {
            texUnit = unit;
        }
    }

    public static void texMatrixMode(int mode) {
        matrixMode = mode;
    }

    public static void texMatrixPush() {
        if (matrixMode == 5890) { // GL_TEXTURE
            TexUnit u = texUnit(texUnit);
            u.stack.push(new Matrix4f(u.cur));
        }
    }

    public static void texMatrixPop() {
        if (matrixMode == 5890) {
            TexUnit u = texUnit(texUnit);
            if (!u.stack.isEmpty()) {
                u.cur.set(u.stack.pop());
            }
        }
    }

    public static void texMatrixLoadIdentity() {
        if (matrixMode == 5890) {
            texUnit(texUnit).cur.identity();
        }
    }

    public static void texMatrixTranslate(float x, float y, float z) {
        if (matrixMode == 5890) {
            texUnit(texUnit).cur.translate(x, y, z);
        }
    }

    public static void texMatrixRotate(float angleDeg, float x, float y, float z) {
        if (matrixMode == 5890) {
            // glRotatef normalizes the axis; joml rotates about the vector as given,
            // so a non-unit axis would scale the texture (R22).
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 1.0e-4f) {
                texUnit(texUnit).cur.rotate((float) Math.toRadians(angleDeg), x / len, y / len, z / len);
            }
        }
    }

    public static void texMatrixScale(float x, float y, float z) {
        if (matrixMode == 5890) {
            texUnit(texUnit).cur.scale(x, y, z);
        }
    }

    public static ByteBuffer getTextureMatrix() {
        TextureMatrix.position(0);
        texUnit(0).cur.get(TextureMatrix); // the atlas/item unit
        TextureMatrix.position(0);
        return TextureMatrix;
    }

    /** Copies texture unit 0's current matrix into dest (no allocation; the End portal replay). */
    public static Matrix4f getTextureMatrix4f(Matrix4f dest) {
        return dest.set(texUnit(0).cur);
    }

    /**
     * Mirrored {@code GL_EYE_PLANE} coefficients of the eye-linear texture coordinate
     * generation. The End portal TESR is the only 1.12.2 code that sets eye planes;
     * {@code GlStateManagerTexGenM} records them here and {@code EndPortalRenderer}
     * replays the generation on the CPU.
     */
    public static final float[] texGenPlaneS = new float[4];
    public static final float[] texGenPlaneT = new float[4];
    public static final float[] texGenPlaneR = new float[4];

    public static ByteBuffer getModelViewMatrix() {
        return modelViewMatrix;
    }

    public static ByteBuffer getProjectionMatrix() {
        return projectionMatrix;
    }

    public static ByteBuffer getMVP() {
        return MVP;
    }

    public static FloatBuffer getProjectionMatrix4f() {
        return projectionMatrixF.position(0).limit(16);
    }

    public static FloatBuffer getModelViewMatrix4f() {
        return modelViewMatrixF.position(0).limit(16);
    }

    public static void setChunkOffset(float f1, float f2, float f3) {
        ChunkOffset.putFloat(0, f1);
        ChunkOffset.putFloat(4, f2);
        ChunkOffset.putFloat(8, f3);
    }

    // --- Shader color / fog color ---

    public static void setShaderColor(float f1, float f2, float f3, float f4) {
        shaderColor[0] = f1;
        shaderColor[1] = f2;
        shaderColor[2] = f3;
        shaderColor[3] = f4;
    }

    public static void setFogShaderColor(float f1, float f2, float f3, float f4) {
        shaderFogColor[0] = f1;
        shaderFogColor[1] = f2;
        shaderFogColor[2] = f3;
        shaderFogColor[3] = f4;
    }

    public static float[] getShaderColor() {
        return shaderColor;
    }

    public static float[] getShaderFogColor() {
        return shaderFogColor;
    }

    // --- Fog state (fed by mixins; FOG_NONE/FOG_LINEAR/FOG_EXP/FOG_EXP2) ---

    public static void setFogState(int state) { fogState = state; }
    public static int getFogState() { return fogState; }

    public static void setFogDistanceMode(int mode) { fogDistanceMode = mode; }

    /** 1 when vanilla requested the GL_EYE_RADIAL_NV fog metric, 0 for the |z| one. */
    public static int getFogRadial() { return fogDistanceMode == 0x855B ? 1 : 0; }

    // --- Fixed-function alpha test (live func/ref), fed to the terrain/entity/GUI shaders ---

    public static void setAlphaTest(boolean enabled) { alphaTest = enabled; }

    public static void setAlphaFunc(int func, float ref) { alphaTestFunc = func; alphaTestRef = ref; }

    /**
     * The live alphaFunc comparison as the shaders consume it: the GL enum (NEVER 512
     * ... ALWAYS 519) while the test is enabled, 0 when it is disabled. The shaders
     * evaluate the real comparison instead of the old hardcoded GREATER 0.1, so non-
     * GREATER funcs (ALWAYS for the fade passes, LESS/GEQUAL from mods) behave like GL
     * instead of turning the test off (M21, N23).
     */
    public static float getAlphaFunc() { return alphaTest ? (float) alphaTestFunc : 0.0f; }

    /** The live alphaFunc reference; meaningful only while {@link #getAlphaFunc()} != 0. */
    public static float getAlphaRef() { return alphaTestRef; }

    public static void setFogStart(float v) { fogStart = v; }
    public static float getFogStart() { return fogStart; }
    public static float getShaderFogStart() { return fogStart; }

    public static void setFogEnd(float v) { fogEnd = v; }
    public static float getFogEnd() { return fogEnd; }
    public static float getShaderFogEnd() { return fogEnd; }

    public static void setFogDensity(float v) { fogDensity = v; }
    public static float getFogDensity() { return fogDensity; }

    public static void setFogEnabled(boolean b) { fogEnabled = b; }
    public static boolean isFogEnabled() { return fogEnabled; }

    // --- Line / point / game time ---

    public static void setLineWidth(float v) { lineWidth = v; }

    /** Clamped to the device's line-width range before it reaches vkCmdSetLineWidth (M3). */
    public static float getLineWidth() { return Math.max(minLineWidth, Math.min(lineWidth, maxLineWidth)); }

    public static void setLineWidthRange(float min, float max) {
        minLineWidth = Math.min(1.0f, Math.max(0.0f, min));
        maxLineWidth = Math.max(minLineWidth, max);
    }
    public static float getShaderLineWidth() { return lineWidth; }

    public static void setPointSize(float v) { pointSize = v; }
    public static float getPointSize() { return pointSize; }

    public static void setGameTime(double v) { gameTime = v; }
    public static double getGameTime() { return gameTime; }
    public static double getShaderGameTime() { return gameTime; }

    // --- Blend / logic op ---

    public static void enableBlend() { blendEnabled = true; }
    public static void disableBlend() { blendEnabled = false; }

    public static void tryBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (srcRgb != blendSrcRgb || dstRgb != blendDstRgb
                || srcAlpha != blendSrcAlpha || dstAlpha != blendDstAlpha) {
            cachedBlendState = null;
        }
        blendSrcRgb = srcRgb;
        blendDstRgb = dstRgb;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
    }

    // BlendState is immutable, so one instance per factor set is shared by every draw.
    // Only the blend setters and resetState write the factors; both drop the cache.
    private static Pipeline.BlendState cachedBlendState;

    public static Pipeline.BlendState getBlendState() {
        if (!blendEnabled) {
            return Pipeline.NO_BLEND_STATE;
        }
        Pipeline.BlendState state = cachedBlendState;
        if (state == null) {
            state = new Pipeline.BlendState(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            cachedBlendState = state;
        }
        return state;
    }

    public static void enableColorLogicOp() {
        logicOpEnabled = true;
        applyLogicOpState();
    }

    public static void disableColorLogicOp() {
        logicOpEnabled = false;
        Drawer.currentLogicOpState = Pipeline.DEFAULT_LOGICOP_STATE;
    }

    public static void logicOp(int mode) {
        logicOpMode = mode;
        applyLogicOpState(); // L12: changing the op must not force-enable it
    }

    // A logic-op pipeline variant is only valid with the logicOp device feature.
    private static void applyLogicOpState() {
        Drawer.currentLogicOpState = logicOpEnabled && Vulkan.logicOpSupported
                ? new Pipeline.LogicOpState(true, logicOpMode)
                : Pipeline.DEFAULT_LOGICOP_STATE;
    }

    // --- Clear ---

    public static void clearColor(float f1, float f2, float f3, float f4) {
        clearColor.put(0, f1);
        clearColor.put(1, f2);
        clearColor.put(2, f3);
        clearColor.put(3, f4);
    }

    public static FloatBuffer getClearColor() {
        return clearColor.position(0);
    }

    public static void clearDepth(float v) { clearDepth = v; }

    public static void clear(int v) {
        Drawer.clearAttachments(v);
    }

    // --- Depth / mask / cull / polygon offset ---

    public static void enableDepthTest() { depthTest = true; }
    public static void disableDepthTest() { depthTest = false; }

    public static void depthMask(boolean b) { depthMask = b; }

    public static void depthFunc(int func) { depthFun = func; }

    public static Pipeline.DepthState getDepthState() {
        return new Pipeline.DepthState(depthTest, depthMask, depthFun);
    }

    public static void colorMask(boolean b, boolean b1, boolean b2, boolean b3) {
        colorMask = Pipeline.ColorMask.getColorMask(b, b1, b2, b3);
    }

    public static int getColorMask() {
        return colorMask;
    }

    public static void enableCull() { cull = true; }
    public static void disableCull() { cull = false; }

    public static void setTopology(int topology) { VRenderSystem.topology = topology; }
    public static int getTopology() { return topology; }

    public static void polygonOffset(float factor, float units) {
        depthBias[0] = factor;
        depthBias[1] = units;
    }

    public static void enablePolygonOffset() {
        // GL: offset = units*r + factor*m; Vulkan: offset = constantFactor + slopeFactor*m,
        // so GL units map to the constant factor and GL factor to the slope factor.
        Drawer.setDepthBias(depthBias[1], depthBias[0]);
    }

    public static void disablePolygonOffset() {
        Drawer.setDepthBias(0.0F, 0.0F);
    }

    // --- Misc ---

    public static Vector2f getScreenSize() {
        Minecraft mc = Minecraft.getMinecraft();
        return new Vector2f((float) mc.displayWidth, (float) mc.displayHeight);
    }

    // Cloud UV scroll offset (M12): the persistent cloud VBO carries the cell-local UV,
    // the per-frame scroll/camera UV offset rides this UBO field instead of the vertices.
    private static final Vector2f cloudUvOffset = new Vector2f();

    public static void setCloudUvOffset(float u, float v) {
        cloudUvOffset.set(u, v);
    }

    public static Vector2f getCloudUvOffset() {
        return cloudUvOffset;
    }

    public static void setWindow(long window) {
        VRenderSystem.window = window;
    }

    public static Pipeline getShaderPipeline() {
        return currentPipeline;
    }

    public static void setShaderPipeline(Pipeline pipeline) {
        currentPipeline = pipeline;
    }

    public static void resetState() {
        depthTest = true;
        depthMask = true;
        depthFun = 515;
        colorMask = Pipeline.ColorMask.getColorMask(true, true, true, true);
        cull = true;
        blendEnabled = false;
        blendSrcRgb = 1;
        blendDstRgb = 0;
        blendSrcAlpha = 1;
        blendDstAlpha = 0;
        cachedBlendState = null;
        alphaTestFunc = 516;
        alphaTestRef = 0.1f;
        logicOpEnabled = false;
        logicOpMode = 0x1503;
        Drawer.currentLogicOpState = Pipeline.DEFAULT_LOGICOP_STATE;
        fogState = FOG_LINEAR;
        fogStart = 0.95f;
        fogEnd = 1.0f;
        fogDensity = 0.1f;
        fogEnabled = true;
        lineWidth = 1.0f;
        pointSize = 1.0f;
        topology = 3; // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
        currentPipeline = null;
        for (TexUnit u : TEX_UNITS) {
            if (u != null) {
                u.cur.identity();
                u.stack.clear();
            }
        }
        texUnit = 0;
        matrixMode = 5888;
    }
}
