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
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * Vulkan port of the 1.12.2 GUI/HUD rendering. The overlay and every {@code GuiScreen}
 * draw their quads through the shared {@link BufferBuilder} and upload them via
 * {@code WorldVertexBufferUploader.draw}, so the same capture hook the entities use
 * works here too: while a GUI pass is active (bracketed by mixins around
 * {@code GuiIngame.renderGameOverlay} and {@code GuiScreen.drawScreen}) the finished
 * geometry is replayed in Vulkan with the live GL matrices, which already carry the
 * {@code setupOverlayRendering} ortho projection and every per-element translate.
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

    /** True between the head and return of the HUD / current screen render calls. */
    private static boolean guiPass;

    /** GL texture id -> uploaded Vulkan image (icons, misc, ascii, atlas, ...). */
    private static final HashMap<Integer, VulkanImage> textureCache = new HashMap<>();

    private static final FloatBuffer float4 = MemoryUtil.memAllocFloat(4);
    private static final ByteBuffer bool1 = MemoryUtil.memAlloc(1);
    private static final ByteBuffer bool4 = MemoryUtil.memAlloc(4);
    private static final java.nio.IntBuffer int1 = MemoryUtil.memAllocInt(1);

    private static boolean loggedFirst;

    /** TEMP diagnostics: one line per distinct GUI-pass draw shape. */
    private static final java.util.HashSet<String> seenShapes = new java.util.HashSet<>();

    private static void logCapture(String tag, BufferBuilder buffer) {
        String shape = tag + " mode=" + buffer.getDrawMode() + " verts=" + buffer.getVertexCount()
                + " fmt=" + buffer.getVertexFormat();
        if (seenShapes.add(shape)) {
            Initializer.LOGGER.info("[gui] {}", shape);
        }
    }

    public static void init() {
        guiPipeline = new Pipeline(POSITION_TEX, "gui");
        guiColorPipeline = new Pipeline(POSITION_COLOR, "gui_color");
        guiTexColorPipeline = new Pipeline(POSITION_TEX_COLOR, "gui_tex_color");
    }

    public static void setGuiPass(boolean value) {
        if (value && !guiPass) {
                // TEMP diagnostics: log the first draws of each GUI pass.
                diagTess = 0;
                diagHookCount = 0;
                diagGlDrawCount = 0;
                diagGuiHelperCount = 0;
        }
        guiPass = value;
    }

    private static int passTransitions;
    private static int diagHookCount;

    /** TEMP diagnostics: allow the first few uploader-hook hits per pass to be logged. */
    public static boolean diagHook() {
        return diagHookCount++ < 6;
    }

    private static int diagTess;

    public static boolean diagTess() {
        return diagTess++ < 6;
    }

    private static boolean firstTessLogged;

    public static boolean firstTess() {
        return !firstTessLogged && !(firstTessLogged = true);
    }

    private static int diagStackCount;

    public static boolean diagStack() {
        return diagStackCount++ < 2;
    }

    private static int diagGlDrawCount;

    public static boolean diagGlDraw() {
        return diagGlDrawCount++ < 3;
    }

    private static int diagAlwaysCount;

    public static boolean diagAlways() {
        return diagAlwaysCount++ < 3;
    }

    private static int diagGuiHelperCount;

    public static boolean diagGuiHelper() {
        return diagGuiHelperCount++ < 4;
    }

    public static boolean isGuiPass() {
        return guiPass;
    }

    /**
     * Called at the head of {@code WorldVertexBufferUploader.draw} during a GUI pass.
     * Returns false for formats the GUI pipelines do not cover, so the caller leaves
     * the draw alone instead of silently dropping it.
     */
    public static boolean capture(BufferBuilder buffer) {
        if (!guiPass || !Drawer.isActive() || Drawer.skipRendering) {
            return false;
        }
        int vertexCount = buffer.getVertexCount();
        int drawMode = buffer.getDrawMode();
        VertexFormat format = buffer.getVertexFormat();
        if (vertexCount <= 0 || (drawMode != 7 && drawMode != 6 && drawMode != 5)) {
            logCapture("skipped mode", buffer);
            return false;
        }
        if (format != POSITION_TEX && format != POSITION_COLOR && format != POSITION_TEX_COLOR) {
            logCapture("skipped format", buffer);
            return false;
        }

        try {
            draw(buffer, drawMode, vertexCount, format);
            return true;
        } catch (Throwable t) {
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

        // --- state mirrored from GL at the GUI draw ---
        VRenderSystem.updateProjectionMatrix();
        VRenderSystem.updateModelViewMatrix();

        // The overlay is drawn with fog disabled; mirror the enable bit only (no fog
        // parameters are snapshotted, so an enabled fog would read stale terrain values).
        boolean fog = boolState(GL11.GL_FOG);
        VRenderSystem.setFogState(fog ? VRenderSystem.FOG_LINEAR : VRenderSystem.FOG_NONE);

        // The alpha test state is mirrored by the GlStateManagerAlphaM mixin (the GL
        // query for it is not reliable under the Kirino layer).

        // See the class doc: the GL depth clear before the GUI has no Vulkan counterpart,
        // so the GUI must not test against the world's depth attachment.
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.depthFunc(GL11.GL_ALWAYS);

        if (boolState(GL11.GL_CULL_FACE)) {
            VRenderSystem.enableCull();
        } else {
            VRenderSystem.disableCull();
        }

        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, bool4);
        VRenderSystem.colorMask(bool4.get(0) != 0, bool4.get(1) != 0, bool4.get(2) != 0, bool4.get(3) != 0);

        if (boolState(GL11.GL_BLEND)) {
            VRenderSystem.enableBlend();
            GL11.glGetIntegerv(GL14.GL_BLEND_SRC_RGB, int1);
            int srcRgb = int1.get(0);
            GL11.glGetIntegerv(GL14.GL_BLEND_DST_RGB, int1);
            int dstRgb = int1.get(0);
            GL11.glGetIntegerv(GL14.GL_BLEND_SRC_ALPHA, int1);
            int srcAlpha = int1.get(0);
            GL11.glGetIntegerv(GL14.GL_BLEND_DST_ALPHA, int1);
            int dstAlpha = int1.get(0);
            VRenderSystem.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        } else {
            VRenderSystem.disableBlend();
        }

        // The current GL colour is the GUI tint (text colour, icon redness, the
        // gradient rect's ambient multiply), feeding the ColorModulator UBO field.
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, float4);
        VRenderSystem.setShaderColor(float4.get(0), float4.get(1), float4.get(2), float4.get(3));

        Pipeline pipeline = format == POSITION_TEX ? guiPipeline
                : format == POSITION_COLOR ? guiColorPipeline
                : guiTexColorPipeline;

        VRenderSystem.setShaderPipeline(pipeline);
        Drawer.currentBlendState = VRenderSystem.getBlendState();
        if (textured) {
            VTextureSelector.bindTexture(texture);
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
     * Maps the GL texture bound on the active unit (icons, misc, the font, the block
     * atlas for hotbar items) to a Vulkan image, downloading and caching on first use.
     */
    private static VulkanImage currentGuiTexture() {
        int texId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texId == 0) {
            return null;
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

        // NEAREST filtering, clamped addressing: SimpleTexture without an mcmeta
        // (icons, misc, ascii) defaults to GL_NEAREST, matching the vanilla look.
        VulkanImage image = new VulkanImage(1, width, height, 4, false, true);
        image.uploadSubTextureAsync(0, width, height, 0, 0, 4, 0, 0, width, pixels);
        MemoryUtil.memFree(pixels);
        return image;
    }

    private static boolean boolState(int pname) {
        GL11.glGetBooleanv(pname, bool1);
        return bool1.get(0) != 0;
    }

    private GuiRenderer() {
    }
}
