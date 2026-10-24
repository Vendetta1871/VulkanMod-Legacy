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
    public static int depthFun = 515; // GL_LESS

    public static int colorMask = Pipeline.ColorMask.getColorMask(true, true, true, true);

    public static boolean cull = true;

    // GL default: the fixed-function alpha test is enabled (GREATER 0.1)
    public static boolean alphaTest = true;

    // --- Current GL colour mirrored from GlStateManager.color (Kirino GL does not
    // answer glGetFloatv(GL_CURRENT_COLOR)); consumed by the GUI pass ---
    public static float trackedColorR = 1, trackedColorG = 1, trackedColorB = 1, trackedColorA = 1;

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
    public static int logicOpMode = 0;   // GL_CLEAR

    public static float clearDepth = 1.0f;
    public static FloatBuffer clearColor = MemoryUtil.memAllocFloat(4);

    static {
        clearColor.put(0, 0.1f).put(1, 0.2f).put(2, 0.5f).put(3, 1.0f);
    }

    /** Mirrors GL_FOG_COLOR into the clear color so the void matches the fog (horizon seam). */
    public static void updateClearColorFromFog() {
        clearColor.position(0);
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_FOG_COLOR, clearColor);
        if (clearColor.get(3) <= 0.0f) {
            clearColor.put(3, 1.0f);
        }
        clearColor.position(0);
    }

    public static ByteBuffer modelViewMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer projectionMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer TextureMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer MVP = MemoryUtil.memAlloc(16 * 4);

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
    private static float fogStart = 0.95f;
    private static float fogEnd = 1.0f;
    private static float fogDensity = 0.1f;
    private static boolean fogEnabled = true;

    private static float lineWidth = 1.0f;
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
                modelViewMatrix.asFloatBuffer().position(0).limit(16));
        calculateMVP();
    }

    public static void updateProjectionMatrix() {
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX,
                projectionMatrix.asFloatBuffer().position(0).limit(16));
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

    public static void calculateMVP() {
        SCRATCH_MV.set(modelViewMatrix.asFloatBuffer().position(0));
        SCRATCH_P.set(projectionMatrix.asFloatBuffer().position(0));

        DEPTH_REMAP.mul(SCRATCH_P, SCRATCH_T1).mul(SCRATCH_MV, SCRATCH_T2);

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
        outModelView.set(modelViewMatrix.asFloatBuffer().position(0));
        SCRATCH_P.set(projectionMatrix.asFloatBuffer().position(0));
        DEPTH_REMAP.mul(SCRATCH_P, outProjRemap);
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

    public static void setTextureMatrix(Matrix4f mat) {
        TextureMatrix.position(0);
        mat.get(TextureMatrix);
    }

    public static ByteBuffer getTextureMatrix() {
        return TextureMatrix;
    }

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
        return projectionMatrix.asFloatBuffer().position(0).limit(16);
    }

    public static FloatBuffer getModelViewMatrix4f() {
        return modelViewMatrix.asFloatBuffer().position(0).limit(16);
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

    // --- Fixed-function alpha test (GREATER 0.1), fed to the GUI shaders ---

    public static void setAlphaTest(boolean enabled) { alphaTest = enabled; }
    public static int getAlphaTest() { return alphaTest ? 1 : 0; }

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
    public static float getLineWidth() { return lineWidth; }
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
        blendSrcRgb = srcRgb;
        blendDstRgb = dstRgb;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
    }

    public static Pipeline.BlendState getBlendState() {
        if (!blendEnabled) {
            return Pipeline.NO_BLEND_STATE;
        }
        return new Pipeline.BlendState(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
    }

    public static void enableColorLogicOp() {
        logicOpEnabled = true;
        Drawer.currentLogicOpState = new Pipeline.LogicOpState(true, logicOpMode);
    }

    public static void disableColorLogicOp() {
        logicOpEnabled = false;
        Drawer.currentLogicOpState = Pipeline.DEFAULT_LOGICOP_STATE;
    }

    public static void logicOp(int mode) {
        logicOpMode = mode;
        Drawer.currentLogicOpState = new Pipeline.LogicOpState(true, mode);
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

    public static void polygonOffset(float v, float v1) {
        depthBias[0] = v;
        depthBias[1] = v1;
    }

    public static void enablePolygonOffset() {
        Drawer.setDepthBias(depthBias[0], depthBias[1]);
    }

    public static void disablePolygonOffset() {
        Drawer.setDepthBias(0.0F, 0.0F);
    }

    // --- Misc ---

    public static Vector2f getScreenSize() {
        Minecraft mc = Minecraft.getMinecraft();
        return new Vector2f((float) mc.displayWidth, (float) mc.displayHeight);
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
        logicOpEnabled = false;
        fogState = FOG_LINEAR;
        fogStart = 0.95f;
        fogEnd = 1.0f;
        fogDensity = 0.1f;
        fogEnabled = true;
        lineWidth = 1.0f;
        pointSize = 1.0f;
        currentPipeline = null;
    }
}
