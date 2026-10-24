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
 * <p>Depth testing is forced off: vanilla clears the GL depth buffer before the GUI
 * ({@code setupOverlayRendering} and the pre-screen {@code clear(256)}), a clear that
 * never reaches the Vulkan depth attachment, so replaying "depth test on" against the
 * leftover world depth would occlude the HUD. Without a depth test the GUI layers
 * purely by submission order, which is what vanilla effectively does as well.</p>
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
    private static int imMode;
    private static int imCount;
    private static float imU, imV;
    private static ByteBuffer imBuffer;
    private static final int IM_STRIDE = 20; // POSITION_TEX: 3 + 2 floats

    private static boolean loggedFirst;
    private static int errorLogs;

    public static void init() {
        guiPipeline = new Pipeline(POSITION_TEX, "gui");
        guiColorPipeline = new Pipeline(POSITION_COLOR, "gui_color");
        guiTexColorPipeline = new Pipeline(POSITION_TEX_COLOR, "gui_tex_color");
    }

    public static void setGuiPass(boolean value) {
        guiPass = value;
    }

    public static boolean isGuiPass() {
        return guiPass;
    }

    public static void setSuppressDraw(boolean value) {
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
                && format != DefaultVertexFormats.ITEM) {
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

        Drawer drawer = Drawer.getInstance();

        boolean item = format == DefaultVertexFormats.ITEM;
        Pipeline pipeline = item ? EntityRenderer.getItemPipeline()
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
            if (GL11.glGetBoolean(GL11.GL_CULL_FACE)) {
                VRenderSystem.enableCull();
            } else {
                VRenderSystem.disableCull();
            }
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

    /**
     * Mirrors the GUI render state from the live GL / tracked {@code GlStateManager}
     * values and binds the pipeline (and texture, when {@code texture} is non-null).
     * Shared by the BufferBuilder replay and the immediate-mode (font) replay.
     */
    private static void applyGuiState(Pipeline pipeline, VulkanImage texture) {
        VRenderSystem.updateProjectionMatrix();
        VRenderSystem.updateModelViewMatrix();

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

    // --- Immediate-mode capture entry points (called by GlStateManagerImmediateM) ---

    public static void immediateBegin(int mode) {
        // ensureRenderPass() last: an unsupported mode must not open a frame.
        if (!guiPass || suppressDraw || Drawer.skipRendering
                || (mode != GL11.GL_TRIANGLE_STRIP && mode != GL11.GL_TRIANGLE_FAN && mode != GL11.GL_QUADS)
                || !ensureRenderPass()) {
            imActive = false;
            return;
        }
        imActive = true;
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
        int texId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
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

    private static VulkanImage downloadTexture(int texId) {
        int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
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
