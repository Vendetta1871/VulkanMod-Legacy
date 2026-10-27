package net.vulkanmod.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * Vulkan port of the 1.12.2 GUI/HUD rendering. The overlay and every {@code GuiScreen}
 * draw their quads through the shared {@link BufferBuilder} and upload them via
 * {@code WorldVertexBufferUploader.draw}, so the same capture hook the entities use
 * works here too: while a GUI pass is active (opened by the {@code setupOverlayRendering}
 * hook in {@code EntityRendererM}, which vanilla calls right before the HUD and before
 * the current screen) the finished geometry is replayed in Vulkan with the live GL
 * matrices, which already carry the {@code setupOverlayRendering} ortho projection and
 * every per-element translate.
 *
 * <p>Three formats cover the GUI: {@code POSITION_TEX} (textured quads, icons, font
 * glyphs), {@code POSITION_COLOR} (flat rectangles) and {@code POSITION_TEX_COLOR}
 * (tinted overlays). Textures (icons, misc, ascii, the block atlas for hotbar items)
 * are downloaded from the GL by texture id and cached, like the entity skins.</p>
 *
 * <p>Flat GUI quads force depth off: vanilla clears the GL depth buffer before the GUI
 * ({@code setupOverlayRendering} and the pre-screen {@code clear(256)}), a clear that
 * never reaches the Vulkan depth attachment, so replaying "depth test on" against the
 * leftover world depth would occlude the HUD. Without a depth test those quads layer
 * purely by submission order, which is what vanilla effectively does as well. The 3D
 * item icons are the exception: they need a real depth test (the chest lid must win
 * over the body's top face, and the enchantment glint covers only the item's own pixels
 * through {@code depthFunc EQUAL}), so the first icon of a pass clears the depth
 * attachment and the icons then test against the fresh buffer.</p>
 */
public class GuiRenderer {

    private static Pipeline guiPipeline;      // POSITION_TEX: ColorModulator * texture
    private static Pipeline guiColorPipeline; // POSITION_COLOR: ColorModulator * vertex colour
    private static Pipeline guiTexColorPipeline; // POSITION_TEX_COLOR: all three multiplied

    private static final VertexFormat POSITION_TEX = DefaultVertexFormats.POSITION_TEX;
    private static final VertexFormat POSITION_COLOR = DefaultVertexFormats.POSITION_COLOR;
    private static final VertexFormat POSITION_TEX_COLOR = DefaultVertexFormats.POSITION_TEX_COLOR;

    /** True from {@code setupOverlayRendering} until the next frame starts. */
    private static boolean guiPass;

    /**
     * True while a framebuffer blit runs ({@code FramebufferM}); its full-screen
     * composite quad must not be replayed as GUI content.
     */
    private static boolean suppressDraw;

    /** GL texture id -> uploaded Vulkan image (icons, misc, ascii, atlas, ...). */
    private static final HashMap<Integer, VulkanImage> textureCache = new HashMap<>();

    /** Stand-in bound in place of the real texture while texturing is disabled. */
    private static VulkanImage whiteTexture;

    // --- Immediate-mode capture ---
    // FontRenderer draws each glyph with glBegin(GL_TRIANGLE_STRIP) / glTexCoord2f /
    // glVertex3f / glEnd, bypassing the BufferBuilder, so the WorldVertexBufferUploader
    // hook never sees text. These mirror that vertex stream (POSITION_TEX) while a GUI
    // pass is active and replay it on glEnd.
    private static boolean imActive;
    private static boolean imWorldText; // entity-pass text: world matrices and fog
    private static int imMode;
    private static int imCount;
    private static float imU, imV;
    private static ByteBuffer imBuffer;
    private static final int IM_STRIDE = 20; // POSITION_TEX: 3 + 2 floats

    // --- glyph batching (M11/N18) ---
    // Two producers share one run: BufferBuilder POSITION_TEX quads (HUD icons,
    // drawTexturedModalRect) and FontRenderer glyphs - one 4-vertex GL_TRIANGLE_STRIP
    // per glyph inside renderString, re-ordered into a quad (appendImmediateGlyph).
    // Consecutive draws that share texture, matrix, colour, blend, alpha and logic op
    // accumulate here and go out as one Vulkan draw; any other draw, a non-glyph
    // immediate run, a string boundary, a blit or the frame end flushes the batch
    // first, so paint order is preserved. The matrices, colour and blend are
    // captured when the batch opens - state may move on before the flush (a colour code
    // or a mod's translate with no draw in between), and the batched vertices belong to
    // the state they were captured under.
    private static ByteBuffer glyphBatch;
    private static int glyphBatchVerts;
    private static VulkanImage glyphTexture;
    private static long glyphMatrixVersion;
    // True when the run holds world-space text (entity pass): flushed with the world
    // text state (live depth and fog, cull off) instead of the GUI overrides.
    private static boolean glyphWorld;
    private static float glyphR, glyphG, glyphB, glyphA;
    private static Pipeline.BlendState glyphBlend;
    // The blend factors are part of the batch identity: the crosshair draws its quad
    // with the inverted (ONE_MINUS_DST_COLOR/ONE_MINUS_SRC_COLOR) func and the hearts
    // that follow share texture, colour and matrix - without this the whole batch
    // would flush under the crosshair's func and the HUD would fade in (bug B).
    private static boolean glyphBlendEnabled;
    private static int glyphBlendSrc, glyphBlendDst, glyphBlendSrcA, glyphBlendDstA;
    // Alpha test rides the pipeline variant key, so it is part of the batch identity too.
    private static boolean glyphAlphaTest;
    private static int glyphAlphaFunc;
    private static float glyphAlphaRef;
    // Logic op rides the pipeline variant key as well (R15).
    private static Pipeline.LogicOpState glyphLogicOp = Pipeline.DEFAULT_LOGICOP_STATE;
    private static final org.joml.Matrix4f glyphModelView = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f glyphProjection = new org.joml.Matrix4f();
    // R7: the modelview the batch was opened under, compared against the live GL matrix
    // before a BufferBuilder quad joins it. The version counter cannot see raw GL11
    // translate/push/pop (common in 1.12 mod GUIs), the GL matrix can.
    private static final float[] glyphMv = new float[16];
    private static final FloatBuffer mvProbe = MemoryUtil.memAllocFloat(16);

    // R7: true between FontRenderer.renderString HEAD and RETURN (FontRendererM). A glyph
    // batch is closed at both ends, so it never spans unrelated calls.
    private static boolean inFontString;
    // Capped at 65536 vertices so the batch stays in the shared UINT16 index range (N25).
    private static final int GLYPH_BATCH_MAX = 65536;

    // The GUI projection is a pass constant (setupOverlayRendering sets it through
    // GlStateManager.ortho); re-read it only after a matrix op bumped the version
    // instead of once per draw (M11).
    private static long projVersion = -1;

    private static boolean loggedFirst;
    private static int errorLogs;

    public static void init() {
        guiPipeline = new Pipeline(POSITION_TEX, "gui");
        guiColorPipeline = new Pipeline(POSITION_COLOR, "gui_color");
        guiTexColorPipeline = new Pipeline(POSITION_TEX_COLOR, "gui_tex_color");
    }

    // GUI-pass overrides of the depth/cull/colour-mask state (applyGuiState writes them
    // directly); saved on pass entry and restored on exit so the next frame's world
    // passes do not inherit "depth off, func ALWAYS, cull off" (N1).
    private static boolean savedDepthTest, savedDepthMask, savedCull;
    private static int savedDepthFun, savedColorMask;

    public static void setGuiPass(boolean value) {
        inFontString = false; // a renderString that threw never reached its RETURN hook
        boolean wasGui = guiPass;
        if (value && !wasGui) {
            savedDepthTest = VRenderSystem.depthTest;
            savedDepthMask = VRenderSystem.depthMask;
            savedDepthFun = VRenderSystem.depthFun;
            savedCull = VRenderSystem.cull;
            savedColorMask = VRenderSystem.colorMask;
        }
        guiPass = value;
        if (value) {
            itemDepthReady = false;
            // The GUI pass (inventory paper-doll, item icons) runs with an orthographic
            // projection; the cached world snapshot must not leak into it (H8).
            EntityRenderer.invalidateFrameState();
        } else if (wasGui) {
            VRenderSystem.depthTest = savedDepthTest;
            VRenderSystem.depthMask = savedDepthMask;
            VRenderSystem.depthFun = savedDepthFun;
            VRenderSystem.cull = savedCull;
            VRenderSystem.colorMask = savedColorMask;
        }
    }

    /**
     * True once the depth attachment has been cleared for the current GUI pass. The 3D
     * item icons (the chest, the block items) need a real depth test: vanilla renders
     * them with depth on, so the lid of the chest wins over the body's black top face,
     * while the port's submission-order-only GUI let the later body quads paint over the
     * lid. Flat GUI quads keep depth off and are unaffected by the leftover depth.
     */
    private static boolean itemDepthReady;

    /**
     * Clears the depth attachment once per GUI pass. Shared by the 3D item icons and
     * the inventory paper doll (N9): both are real depth-tested geometry drawn into an
     * overlay whose flat quads ignore depth, so they need the leftover world depth gone.
     */
    public static void ensureDepthCleared() {
        if (!itemDepthReady) {
            Drawer.clearAttachments(0x100);
            itemDepthReady = true;
        }
    }

    public static boolean isGuiPass() {
        return guiPass;
    }

    /** The POSITION_TEX_COLOR GUI pipeline, reused for textured world lines (dragon beams). */
    public static Pipeline getTexColorPipeline() {
        return guiTexColorPipeline;
    }

    /** The Vulkan image mirrored from the texture bound on unit 0 (null when unknown). */
    public static VulkanImage trackedTexture() {
        return currentGuiTexture();
    }

    public static void setSuppressDraw(boolean value) {
        if (value) {
            flushGlyphBatch(); // the blit composites over what was drawn before it
        }
        suppressDraw = value;
    }

    private static long renderedFrames;

    /** Counts rendered frames ({@code updateCameraAndRender} entries); the autotest
     *  gates the title capture on this so it waits for the menu to actually render. */
    public static void recordFrameBegin() {
        renderedFrames++;
    }

    public static long getRenderedFrames() {
        return renderedFrames;
    }

    /**
     * Makes sure a Vulkan render pass is recording before GUI geometry is replayed.
     *
     * <p>Ordinary frames open the pass in {@code EntityRendererM} at
     * {@code updateCameraAndRender} HEAD, so for them this is a no-op. The world-loading
     * screen is the exception: {@code LoadingScreenRenderer} renders in a synchronous loop
     * driven by {@code Minecraft.launchIntegratedServer} and presents through its own
     * {@code updateDisplay} call, entirely outside that lifecycle. The menu's pass has
     * already been closed by then, so its quads and glyphs would be dropped and the screen
     * would show nothing but the clear colour. Opening the pass here lets those draws record
     * into a frame which that same {@code updateDisplay} then presents - the draws and the
     * present are paired one-for-one inside the loader's own 100 ms throttle, so a pass is
     * never left open without a matching present.</p>
     */
    private static boolean ensureRenderPass() {
        if (Drawer.isActive()) {
            return true;
        }
        Initializer.beginFrame();
        return Drawer.isActive();
    }

    /**
     * Called at the head of {@code WorldVertexBufferUploader.draw} during a GUI pass.
     * Returns false for formats the GUI pipelines do not cover, so the caller leaves
     * the draw alone instead of silently dropping it.
     */
    public static boolean capture(BufferBuilder buffer) {
        if (!guiPass || suppressDraw || Drawer.skipRendering) {
            return false;
        }
        int vertexCount = buffer.getVertexCount();
        int drawMode = buffer.getDrawMode();
        VertexFormat format = buffer.getVertexFormat();
        if (vertexCount <= 0 || (drawMode != 7 && drawMode != 6 && drawMode != 5)) {
            return false;
        }
        if (format != POSITION_TEX && format != POSITION_COLOR && format != POSITION_TEX_COLOR
                && format != DefaultVertexFormats.ITEM && format != DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL) {
            return false;
        }
        // Only after the cheap rejections, so a draw we would not replay never opens a frame.
        if (!ensureRenderPass()) {
            return false;
        }

        try {
            draw(buffer, drawMode, vertexCount, format);
            return true;
        } catch (Throwable t) {
            if (errorLogs++ < 3) {
                Initializer.LOGGER.error("[gui] draw error (mode={} verts={} fmt={})", drawMode, vertexCount, format, t);
            }
            TerrainRenderer.reportOnce("gui", t);
            return false;
        }
    }

    private static void draw(BufferBuilder buffer, int drawMode, int vertexCount, VertexFormat format) {
        boolean textured = format != POSITION_COLOR;

        VulkanImage texture = null;
        if (textured) {
            texture = currentGuiTexture();
            if (texture == null) {
                return;
            }
        }

        // Glyph batching (M11/N18): consecutive icon quads that share texture, colour
        // and matrix accumulate into one draw.
        if (format == POSITION_TEX && drawMode == 7) {
            if (vertexCount <= GLYPH_BATCH_MAX) {
                long version = VRenderSystem.getMatrixVersion();
                if (glyphBatchVerts > 0 && (glyphWorld || !glyphStateMatches(texture, version)
                        || !liveModelViewMatchesBatch())) {
                    flushGlyphBatch();
                }
                if (glyphBatchVerts + vertexCount > GLYPH_BATCH_MAX) {
                    flushGlyphBatch();
                }
                if (glyphBatchVerts == 0) {
                    startGlyphBatch(texture, version, false);
                }
                appendGlyphBatch(buffer, vertexCount);
                return;
            }
            flushGlyphBatch(); // oversized quad draw: keep the submission order
        } else {
            flushGlyphBatch();
        }

        Drawer drawer = Drawer.getInstance();

        // OLDMODEL_POSITION_TEX_NORMAL arrives from the TESR dispatcher of builtin item
        // icons (the chest): each box quad is a self-contained begin+draw batch. The
        // entity pipeline replays them; the CPU lightmap multiply lives in the entity
        // submit path, so GUI icons stay fullbright like vanilla's lit item render.
        boolean model = format == DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL;
        boolean item = format == DefaultVertexFormats.ITEM || model;
        Pipeline pipeline = model ? EntityRenderer.getEntityPipeline()
                : format == DefaultVertexFormats.ITEM ? EntityRenderer.getItemPipeline()
                : format == POSITION_TEX ? guiPipeline
                : format == POSITION_COLOR ? guiColorPipeline
                : guiTexColorPipeline;

        applyGuiState(pipeline, texture);

        if (item) {
            // Item models are solid 3D geometry: vanilla leaves back-face culling on from
            // the world pass, and without it the far faces of a GUI block icon paint over
            // the near ones. The GUI forces culling off for its flat quads, so restore the
            // live state (the cull query is one Kirino does answer). The hurt tint must
            // never leak from the last entity draw into an icon.
            EntityRenderer.clearRedOverlay();
            // applyGuiState just cleared the live cull bit, so read the GL-truth mirror
            // (GlStateManagerDepthM) - the state the world pass actually left (N16).
            if (VRenderSystem.glCull) {
                VRenderSystem.enableCull();
            } else {
                VRenderSystem.disableCull();
            }
            // Vanilla draws the 3D icons with depth testing on (the GUI transform pushes
            // them to z=100); the port's GUI otherwise layers purely by submission, which
            // lets the chest body's top face overwrite the lid drawn before it. Clear the
            // leftover world depth once per pass and test against a fresh buffer.
            ensureDepthCleared();
            VRenderSystem.enableDepthTest();
            VRenderSystem.depthMask(true);
            // The enchantment glint covers only the item's own pixels via depthFunc EQUAL
            // against the depth the icon wrote just before; every other icon face tests
            // normally. (The glint is the ITEM draw with the SRC_COLOR/ONE additive blend.)
            boolean glint = format == DefaultVertexFormats.ITEM && VRenderSystem.blendEnabled
                    && VRenderSystem.blendSrcRgb == 768 && VRenderSystem.blendDstRgb == 1;
            VRenderSystem.depthFunc(glint ? GL11.GL_EQUAL : GL11.GL_LEQUAL);
        }

        ByteBuffer data = buffer.getByteBuffer();
        int prev = data.position();
        data.position(0);
        drawer.draw(data, drawMode, format, vertexCount);
        data.position(prev);

        if (!loggedFirst) {
            loggedFirst = true;
            Initializer.LOGGER.info("[gui] first GUI draw: {} verts, mode {}, format {}", vertexCount, drawMode, format);
        }
    }

    /** True when the live state still matches the state the open batch was captured under. */
    private static boolean glyphStateMatches(VulkanImage texture, long version) {
        return texture == glyphTexture && version == glyphMatrixVersion
                && VRenderSystem.trackedColorR == glyphR && VRenderSystem.trackedColorG == glyphG
                && VRenderSystem.trackedColorB == glyphB && VRenderSystem.trackedColorA == glyphA
                && VRenderSystem.blendEnabled == glyphBlendEnabled
                && VRenderSystem.blendSrcRgb == glyphBlendSrc
                && VRenderSystem.blendDstRgb == glyphBlendDst
                && VRenderSystem.blendSrcAlpha == glyphBlendSrcA
                && VRenderSystem.blendDstAlpha == glyphBlendDstA
                && VRenderSystem.getAlphaFunc() == (glyphAlphaTest ? glyphAlphaFunc : 0.0f)
                && VRenderSystem.getAlphaRef() == glyphAlphaRef
                && Drawer.currentLogicOpState.equals(glyphLogicOp);
    }

    /** Opens a glyph batch: captures the state the accumulated quads belong to (M11/N18). */
    private static void startGlyphBatch(VulkanImage texture, long version, boolean world) {
        if (glyphBatch == null) {
            glyphBatch = MemoryUtil.memAlloc(GLYPH_BATCH_MAX * IM_STRIDE);
        }
        glyphBatch.position(0);
        glyphWorld = world;
        glyphTexture = texture;
        glyphMatrixVersion = version;
        glyphR = VRenderSystem.trackedColorR;
        glyphG = VRenderSystem.trackedColorG;
        glyphB = VRenderSystem.trackedColorB;
        glyphA = VRenderSystem.trackedColorA;
        glyphBlend = VRenderSystem.getBlendState();
        glyphBlendEnabled = VRenderSystem.blendEnabled;
        glyphBlendSrc = VRenderSystem.blendSrcRgb;
        glyphBlendDst = VRenderSystem.blendDstRgb;
        glyphBlendSrcA = VRenderSystem.blendSrcAlpha;
        glyphBlendDstA = VRenderSystem.blendDstAlpha;
        glyphAlphaTest = VRenderSystem.alphaTest;
        glyphAlphaFunc = VRenderSystem.alphaTestFunc;
        glyphAlphaRef = VRenderSystem.alphaTestRef;
        glyphLogicOp = Drawer.currentLogicOpState;
        VRenderSystem.updateProjectionMatrix();
        VRenderSystem.updateModelViewMatrix();
        VRenderSystem.getModelViewMatrix4f().get(glyphMv);
        glyphModelView.set(glyphMv);
        glyphProjection.set(VRenderSystem.getProjectionMatrix4f());
    }

    /** One glGet per BufferBuilder quad: exact against raw GL11 matrix calls (R7). */
    private static boolean liveModelViewMatchesBatch() {
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, mvProbe.clear());
        for (int i = 0; i < 16; i++) {
            if (mvProbe.get(i) != glyphMv[i]) {
                return false;
            }
        }
        return true;
    }

    /** FontRendererM, renderString HEAD: nothing drawn before the string joins its batch. */
    public static void beginFontString() {
        flushGlyphBatch();
        inFontString = true;
    }

    /** FontRendererM, renderString RETURN: the string's glyph run ends here. */
    public static void endFontString() {
        inFontString = false;
        flushGlyphBatch();
    }

    /**
     * Appends the FontRenderer glyph just captured in imBuffer (M11/N18). Inside
     * renderString no matrix op runs, so the matrix version alone proves the modelview
     * captured at batch start is still current - no glGetFloatv per glyph.
     */
    private static void appendImmediateGlyph(VulkanImage texture) {
        long version = VRenderSystem.getMatrixVersion();
        if (glyphBatchVerts > 0 && (glyphWorld != imWorldText || !glyphStateMatches(texture, version))) {
            flushGlyphBatch();
        }
        if (glyphBatchVerts + 4 > GLYPH_BATCH_MAX) {
            flushGlyphBatch();
        }
        if (glyphBatchVerts == 0) {
            startGlyphBatch(texture, version, imWorldText);
        }
        // Strip order v0 v1 v2 v3 -> quad order v0 v1 v3 v2: the same two triangles.
        copyImVertex(0);
        copyImVertex(1);
        copyImVertex(3);
        copyImVertex(2);
        glyphBatchVerts += 4;
    }

    /** imBuffer and the batch share the POSITION_TEX layout (3 + 2 floats). */
    private static void copyImVertex(int i) {
        int o = i * IM_STRIDE;
        glyphBatch.putFloat(imBuffer.getFloat(o)).putFloat(imBuffer.getFloat(o + 4))
                .putFloat(imBuffer.getFloat(o + 8)).putFloat(imBuffer.getFloat(o + 12))
                .putFloat(imBuffer.getFloat(o + 16));
    }

    /** Copies one glyph's four textured vertices into the batch (positions are raw). */
    private static void appendGlyphBatch(BufferBuilder buffer, int vertexCount) {
        ByteBuffer src = buffer.getByteBuffer();
        int prevPos = src.position();
        int prevLimit = src.limit();
        src.position(0);
        src.limit(vertexCount * POSITION_TEX.getSize());
        glyphBatch.put(src);
        src.position(prevPos);
        src.limit(prevLimit);
        glyphBatchVerts += vertexCount;
    }

    /**
     * Issues the accumulated glyphs under the state captured when the batch opened -
     * the matrix may have moved on since the last append (a colour code or a translate
     * with no draw in between), and the vertices belong to the old one.
     */
    private static void flushGlyphBatch() {
        if (glyphBatchVerts <= 0) {
            return;
        }
        int n = glyphBatchVerts;
        glyphBatchVerts = 0;
        if (!Drawer.isActive()) {
            return; // the frame closed without a flush point; dropping beats a stale draw
        }
        try {
            // Draw under the state captured at batch start; the live state may have moved
            // on (a colour code, an alpha toggle with no draw in between), and the batched
            // vertices belong to the old one. The live state is restored afterwards so
            // later draws see what they expect.
            boolean liveAlphaTest = VRenderSystem.alphaTest;
            int liveAlphaFunc = VRenderSystem.alphaTestFunc;
            float liveAlphaRef = VRenderSystem.alphaTestRef;
            Pipeline.BlendState liveBlend = Drawer.currentBlendState;
            Pipeline.LogicOpState liveLogicOp = Drawer.currentLogicOpState;
            // applyWorldTextState overrides cull and the colour mask (N17).
            boolean liveCull = VRenderSystem.cull;
            int liveColorMask = VRenderSystem.colorMask;
            VRenderSystem.alphaTest = glyphAlphaTest;
            VRenderSystem.alphaTestFunc = glyphAlphaFunc;
            VRenderSystem.alphaTestRef = glyphAlphaRef;
            try {
                VRenderSystem.applyModelViewMatrix(glyphModelView);
                VRenderSystem.applyProjectionMatrix(glyphProjection);
                VRenderSystem.calculateMVP();
                if (glyphWorld) {
                    applyWorldTextState(guiPipeline, glyphTexture, false);
                } else {
                    applyGuiState(guiPipeline, glyphTexture, false);
                }
                VRenderSystem.setShaderColor(glyphR, glyphG, glyphB, glyphA);
                Drawer.currentBlendState = glyphBlend;
                Drawer.currentLogicOpState = glyphLogicOp;
                glyphBatch.position(0);
                Drawer.getInstance().draw(glyphBatch, 7, POSITION_TEX, n);
            } finally {
                VRenderSystem.alphaTest = liveAlphaTest;
                VRenderSystem.alphaTestFunc = liveAlphaFunc;
                VRenderSystem.alphaTestRef = liveAlphaRef;
                Drawer.currentBlendState = liveBlend;
                Drawer.currentLogicOpState = liveLogicOp;
                if (glyphWorld) {
                    VRenderSystem.cull = liveCull;
                    VRenderSystem.colorMask = liveColorMask;
                }
            }
        } catch (Throwable t) {
            if (errorLogs++ < 3) {
                Initializer.LOGGER.error("[gui] glyph batch draw error (verts={})", n, t);
            }
        }
    }

    /**
     * Flushes a pending glyph batch; called before the frame is closed and presented
     * (Initializer.presentFrame) so the batch lands in the frame its draws came from.
     */
    public static void flushPendingDraws() {
        flushGlyphBatch();
    }

    /**
     * Mirrors the GUI render state from the live GL / tracked {@code GlStateManager}
     * values and binds the pipeline (and texture, when {@code texture} is non-null).
     * Shared by the BufferBuilder replay and the immediate-mode (font) replay.
     */
    private static void applyGuiState(Pipeline pipeline, VulkanImage texture) {
        applyGuiState(pipeline, texture, true);
    }

    /** @param readMatrices false when the caller installed its own captured matrices. */
    private static void applyGuiState(Pipeline pipeline, VulkanImage texture, boolean readMatrices) {
        if (readMatrices) {
            // M11: the projection is a pass constant (setupOverlayRendering sets it via
            // GlStateManager.ortho), so re-read it only after a matrix op bumped the
            // version instead of once per draw. The modelview moves with every element,
            // so it stays a fresh query.
            long version = VRenderSystem.getMatrixVersion();
            if (version != projVersion) {
                VRenderSystem.updateProjectionMatrix();
                projVersion = version;
            }
            VRenderSystem.updateModelViewMatrix();
        }

        // The overlay is drawn with fog disabled and the Kirino GL layer does not
        // answer the fog query, so the GUI never fogs (matching vanilla's look).
        VRenderSystem.setFogState(VRenderSystem.FOG_NONE);

        // The alpha/blend/colour states are mirrored by the GlStateManagerAlphaM and
        // GlStateManagerBlendM mixins (GL state queries are not reliable under Kirino).

        // See the class doc: the GL depth clear before the GUI has no Vulkan counterpart,
        // so the GUI must not test against the world's depth attachment.
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.depthFunc(GL11.GL_ALWAYS);

        // The GUI is two-sided; the cull query is not reliable, and vanilla leaves
        // culling disabled for the overlay anyway.
        VRenderSystem.disableCull();

        VRenderSystem.colorMask(true, true, true, true);

        // The tracked current GL colour is the GUI tint (text colour, icon redness,
        // the gradient rect's ambient multiply), feeding the ColorModulator UBO field.
        VRenderSystem.setShaderColor(VRenderSystem.trackedColorR, VRenderSystem.trackedColorG,
                VRenderSystem.trackedColorB, VRenderSystem.trackedColorA);

        VRenderSystem.setShaderPipeline(pipeline);
        // Blend mirrored from the GlStateManagerBlendM tracking (the GL query is dead
        // under Kirino). Vanilla overlays use non-standard funcs - the vignette
        // multiplies with ZERO/SRC_COLOR - so the factors must be replayed as-is.
        Drawer.currentBlendState = VRenderSystem.getBlendState();
        if (texture != null) {
            VTextureSelector.bindTexture(texture);
        }
    }

    /**
     * Pipeline state for world-space text (name tags, sign text) captured during the
     * entity pass. Unlike the GUI overlay it replays with the live world matrices and
     * keeps the frame's fog, so the text fades with distance like vanilla; depth,
     * blend, alpha test and colour come from the GlStateManager tracking exactly as
     * the vanilla label code left them (depth test on, depth mask off, blended).
     * Culling is forced off so the billboard stays visible from behind, where vanilla
     * shows it mirrored.
     */
    private static void applyWorldTextState(Pipeline pipeline, VulkanImage texture) {
        applyWorldTextState(pipeline, texture, true);
    }

    /** @param readMatrices false when the caller installed its own captured matrices. */
    private static void applyWorldTextState(Pipeline pipeline, VulkanImage texture, boolean readMatrices) {
        if (readMatrices) {
            VRenderSystem.updateProjectionMatrix();
            VRenderSystem.updateModelViewMatrix();
        }

        VRenderSystem.disableCull();

        VRenderSystem.colorMask(true, true, true, true);
        VRenderSystem.setShaderColor(VRenderSystem.trackedColorR, VRenderSystem.trackedColorG,
                VRenderSystem.trackedColorB, VRenderSystem.trackedColorA);

        VRenderSystem.setShaderPipeline(pipeline);
        Drawer.currentBlendState = VRenderSystem.getBlendState();
        if (texture != null) {
            VTextureSelector.bindTexture(texture);
        }
    }

    // --- Immediate-mode capture entry points (called by GlStateManagerImmediateM) ---

    public static void immediateBegin(int mode) {
        // A pending glyph batch was submitted before this run's draw, so it must go out
        // first (submission order is the GUI's paint order) - unless this strip is the
        // next glyph of the same string, which immediateEnd may append to the run.
        if (!inFontString || mode != GL11.GL_TRIANGLE_STRIP) {
            flushGlyphBatch();
        }
        // World-space text (name tags, sign text) is drawn by FontRenderer inside the
        // entity pass; it replays with the live world matrices instead of the overlay.
        boolean worldText = !guiPass && EntityRenderer.isEntityPass();
        // ensureRenderPass() last: an unsupported mode must not open a frame.
        if ((!guiPass && !worldText) || suppressDraw || Drawer.skipRendering
                || (mode != GL11.GL_TRIANGLE_STRIP && mode != GL11.GL_TRIANGLE_FAN && mode != GL11.GL_QUADS)
                || !ensureRenderPass()) {
            imActive = false;
            return;
        }
        imActive = true;
        imWorldText = worldText;
        imMode = mode;
        imCount = 0;
        if (imBuffer != null) {
            imBuffer.clear();
        }
    }

    public static void immediateTexCoord(float u, float v) {
        if (imActive) {
            imU = u;
            imV = v;
        }
    }

    public static void immediateVertex(float x, float y, float z) {
        if (!imActive) {
            return;
        }
        if (imBuffer == null) {
            imBuffer = MemoryUtil.memAlloc(IM_STRIDE * 4096);
        }
        if ((imCount + 1) * IM_STRIDE > imBuffer.capacity()) {
            imActive = false; // oversized primitive: drop it rather than overflow
            return;
        }
        imBuffer.putFloat(x).putFloat(y).putFloat(z).putFloat(imU).putFloat(imV);
        imCount++;
    }

    public static void immediateEnd() {
        if (!imActive) {
            return;
        }
        imActive = false;
        int count = imCount;
        imCount = 0;
        if (count <= 0) {
            return;
        }
        try {
            VulkanImage texture = currentGuiTexture();
            if (texture == null) {
                return;
            }
            // M11/N18: a FontRenderer glyph (one 4-vertex strip inside renderString)
            // joins the glyph run instead of costing a draw and a glGetFloatv each.
            if (inFontString && imMode == GL11.GL_TRIANGLE_STRIP && count == 4) {
                appendImmediateGlyph(texture);
                return;
            }
            flushGlyphBatch(); // immediateBegin kept the run open for this strip
            if (imWorldText) {
                // applyWorldTextState overrides cull and the colour mask; the rest of
                // the entity pass must not inherit them (N17).
                boolean cull = VRenderSystem.cull;
                int colorMask = VRenderSystem.colorMask;
                try {
                    applyWorldTextState(guiPipeline, texture);
                    imBuffer.position(0);
                    Drawer.getInstance().draw(imBuffer, imMode, POSITION_TEX, count);
                } finally {
                    VRenderSystem.cull = cull;
                    VRenderSystem.colorMask = colorMask;
                }
                return;
            }
            applyGuiState(guiPipeline, texture);
            imBuffer.position(0);
            Drawer.getInstance().draw(imBuffer, imMode, POSITION_TEX, count);
        } catch (Throwable t) {
            if (errorLogs++ < 3) {
                Initializer.LOGGER.error("[gui] immediate draw error (mode={} verts={})", imMode, count, t);
            }
        }
    }

    /**
     * Maps the GL texture bound on the active unit (icons, misc, the font, the block
     * atlas for hotbar items) to a Vulkan image, downloading and caching on first use.
     *
     * <p>When fixed-function texturing is disabled (vanilla draws GuiSlot's scrollbar and
     * its top/bottom fades with {@code disableTexture2D} so only the vertex colour shows)
     * a white image is returned instead: the Vulkan pipelines always sample, so multiplying
     * by white reproduces vanilla's "colour only" result exactly.</p>
     */
    private static VulkanImage currentGuiTexture() {
        if (!VRenderSystem.textureEnabled) {
            if (whiteTexture == null) {
                whiteTexture = VulkanImage.createWhiteTexture();
            }
            return whiteTexture;
        }
        // Tracked bind instead of a per-draw glGetInteger (see EntityRenderer.entityTexture);
        // stale mirrors are dropped by the AbstractTextureM / ThreadDownloadImageDataM
        // invalidation hooks, so no per-draw size query is needed either.
        int texId = VRenderSystem.trackedBoundTexture;
        if (texId == 0) {
            return null;
        }
        // Item icons bind the block atlas: use the terrain's live mirror instead of a
        // 16 MB snapshot (which would also freeze animated tiles).
        if (texId == TextureBridge.getAtlasGlId() && TextureBridge.getAtlasImage() != null) {
            return TextureBridge.getAtlasImage();
        }
        VulkanImage cached = textureCache.get(texId);
        if (cached != null) {
            return cached;
        }
        VulkanImage image = downloadTexture(texId);
        if (image != null) {
            textureCache.put(texId, image);
        }
        return image;
    }

    /** Forgets the Vulkan image mirrored from a GL texture that is being deleted. */
    public static void onTextureDeleted(int texId) {
        VulkanImage image = textureCache.remove(texId);
        if (image != null) {
            image.free();
        }
    }

    private static VulkanImage downloadTexture(int texId) {
        int prevBinding = VRenderSystem.trackedBoundTexture;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBinding);
            return null;
        }

        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBinding);
        pixels.position(0);
        pixels.limit(width * height * 4);

        // NEAREST filtering, REPEAT addressing: SimpleTexture without an mcmeta defaults
        // to GL_NEAREST, and GL's default wrap is GL_REPEAT - which the tiled dirt options
        // background relies on (its UVs run past 1.0). Clamping there stretched the edge
        // texel into solid bands; REPEAT tiles it like vanilla. Icons and glyphs use UVs
        // inside [0,1], where REPEAT and CLAMP are indistinguishable.
        VulkanImage image = new VulkanImage(1, width, height, 4, false, false);
        image.uploadSubTextureAsync(0, width, height, 0, 0, 4, 0, 0, width, pixels);
        MemoryUtil.memFree(pixels);
        return image;
    }

    private GuiRenderer() {
    }
}
