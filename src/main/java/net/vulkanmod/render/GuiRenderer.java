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
     * Called at the head of {@code WorldVertexBufferUploader.draw} during a GUI pass.
     * Returns false for formats the GUI pipelines do not cover, so the caller leaves
     * the draw alone instead of silently dropping it.
     */
    public static boolean capture(BufferBuilder buffer) {
        if (!guiPass || suppressDraw || !Drawer.isActive() || Drawer.skipRendering) {
            return false;
        }
        int vertexCount = buffer.getVertexCount();
        int drawMode = buffer.getDrawMode();
        VertexFormat format = buffer.getVertexFormat();
        if (vertexCount <= 0 || (drawMode != 7 && drawMode != 6 && drawMode != 5)) {
            return false;
        }
        if (format != POSITION_TEX && format != POSITION_COLOR && format != POSITION_TEX_COLOR) {
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

        // --- state mirrored from GL at the GUI draw ---
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

        Pipeline pipeline = format == POSITION_TEX ? guiPipeline
                : format == POSITION_COLOR ? guiColorPipeline
                : guiTexColorPipeline;

        VRenderSystem.setShaderPipeline(pipeline);
        // Blend mirrored from the GlStateManagerBlendM tracking (the GL query is dead
        // under Kirino). Vanilla overlays use non-standard funcs - the vignette
        // multiplies with ZERO/SRC_COLOR - so the factors must be replayed as-is.
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

    private GuiRenderer() {
    }
}
