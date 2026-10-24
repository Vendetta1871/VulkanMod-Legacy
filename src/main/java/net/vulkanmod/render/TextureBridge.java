package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.TransferQueue;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Bridges the GL-owned textures of 1.12.2 (the block atlas and the lightmap) into
 * Vulkan images.
 *
 * <p>The lightmap is a {@link DynamicTexture} whose CPU-side {@code int[]} is read
 * directly (no GL round-trip) once per frame. Animated sprite frames are mirrored into
 * the Vulkan atlas tile-by-tile from their CPU pixel data (see {@link #uploadSpriteTile}),
 * so the full {@code glGetTexImage} download - which forces a GPU pipeline flush on Mesa -
 * only runs once, after the atlas is (re)stitched.</p>
 */
public class TextureBridge {

    private static VulkanImage atlasImage;
    /** GL texture id the mirrored {@link #atlasImage} was uploaded from (restitch changes it). */
    private static int atlasGlId = -1;
    private static VulkanImage lightmapImage;

    private static boolean atlasDirty = true;
    private static int atlasWidth = -1;
    private static int atlasHeight = -1;

    /** Reusable CPU staging buffer for mirroring animated sprite tiles (ARGB -&gt; RGBA). */
    private static ByteBuffer tileBuffer;

    /** Throwaway diagnostic: VULKANMOD_NO_TILEMIRROR=1 restores the full atlas re-download. */
    private static final boolean NO_TILEMIRROR = System.getenv("VULKANMOD_NO_TILEMIRROR") != null;

    /** True while the per-tick tile uploads share one transfer command buffer. */
    private static boolean tileBatchOpen;

    /**
     * Opens the shared transfer command buffer for the tile uploads of one animation tick
     * (TextureMapM brackets {@code TextureMap.updateAnimations} with these), so a tick with a
     * dozen animated sprites costs one queue submit instead of a dozen.
     */
    public static void beginTileBatch() {
        if (NO_TILEMIRROR || tileBatchOpen || !Initializer.isVulkanActive()) {
            return;
        }
        TransferQueue.startRecording();
        tileBatchOpen = true;
    }

    public static void endTileBatch() {
        if (tileBatchOpen) {
            tileBatchOpen = false;
            TransferQueue.endRecording();
        }
    }

    /** Set once per render frame so the lightmap is refreshed at most once per frame. */
    private static boolean updatedThisFrame;

    /** Reusable CPU staging buffer for the 16x16 RGBA lightmap. */
    private static ByteBuffer lightmapBuffer;

    /** Cached handle of the private {@code EntityRenderer.lightmapTexture} field. */
    private static Field lightmapField;

    /** Called after the atlas is (re)stitched, see TextureMapM. */
    public static void markAtlasDirty() {
        atlasDirty = true;
    }

    /**
     * Mirrors one animated sprite frame straight into the Vulkan atlas from the CPU pixel
     * data vanilla has just uploaded to GL (see TextureAtlasSpriteM), instead of re-downloading
     * the whole atlas with glGetTexImage. The int[] is TYPE_INT_ARGB, the same source the GL
     * upload uses, so the mirrored bytes match the GL atlas one-for-one.
     */
    public static void uploadSpriteTile(int[] argb, int width, int height, int x, int y) {
        try {
            if (NO_TILEMIRROR) {
                atlasDirty = true;
                return;
            }
            // Atlas not created yet, or mipmap-padded frame data: fall back to the full download.
            if (atlasImage == null || argb.length != width * height) {
                atlasDirty = true;
                return;
            }
            int size = width * height * 4;
            if (tileBuffer == null || tileBuffer.capacity() < size) {
                if (tileBuffer != null) {
                    MemoryUtil.memFree(tileBuffer);
                }
                tileBuffer = MemoryUtil.memAlloc(size);
            }
            tileBuffer.clear();
            for (int pixel : argb) {
                tileBuffer.put((byte) (pixel >> 16));
                tileBuffer.put((byte) (pixel >> 8));
                tileBuffer.put((byte) pixel);
                tileBuffer.put((byte) (pixel >>> 24));
            }
            tileBuffer.position(0);
            tileBuffer.limit(size);

            atlasImage.uploadSubTextureAsync(0, width, height, x, y, 4, 0, 0, width, tileBuffer);
        } catch (Exception | LinkageError e) {
            Initializer.LOGGER.error("Failed to mirror sprite tile, falling back to atlas download", e);
            atlasDirty = true;
        }
    }

    /** Called at the start of every render frame (see {@link Initializer#beginFrame()}). */
    public static void beginFrame() {
        updatedThisFrame = false;
    }

    public static void updateTextures() {
        try {
            // The lightmap changes with smooth lighting every frame but is identical for
            // all four block layers, so refresh it only on the first layer pass.
            if (!updatedThisFrame) {
                updatedThisFrame = true;
                updateLightmap();
            }
            // The atlas is only re-downloaded when an animation advanced or it was restitched.
            if (atlasDirty) {
                updateAtlas();
            } else {
                // The entity, sky and cloud passes rebind Sampler0 to their own textures, and
                // the translucent (water) chunk layer runs right after the entity pass. The
                // terrain pipeline reads the currently bound image when its descriptor set is
                // written, so every layer pass must restore the atlas even when its pixels are
                // unchanged; otherwise water sampled the last entity's skin and rendered black.
                restoreAtlasBinding();
            }
        } catch (Exception | LinkageError e) {
            Initializer.LOGGER.error("Failed to update Vulkan textures", e);
        }
    }

    private static void updateAtlas() {
        Minecraft mc = Minecraft.getMinecraft();
        TextureMap blocks = mc.getTextureMapBlocks();
        if (blocks == null) {
            return;
        }

        int texId = blocks.getGlTextureId();
        if (texId == 0) {
            return;
        }

        int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBinding);
        pixels.position(0);
        pixels.limit(width * height * 4);

        if (atlasImage == null || width != atlasWidth || height != atlasHeight) {
            if (atlasImage != null) {
                atlasImage.free();
            }
            // NEAREST filtering (vanilla atlas), clamped addressing; single mip level.
            atlasImage = new VulkanImage(1, width, height, 4, false, true);
            atlasWidth = width;
            atlasHeight = height;
        }

        atlasImage.uploadSubTextureAsync(0, width, height, 0, 0, 4, 0, 0, width, pixels);
        MemoryUtil.memFree(pixels);
        atlasGlId = texId;

        VTextureSelector.bindTexture(atlasImage);
        atlasDirty = false;
        if (!atlasLogged) {
            atlasLogged = true;
            Initializer.LOGGER.info("[texture] atlas uploaded: glId={} {}x{} id={}", texId, width, height,
                    System.identityHashCode(atlasImage));
        }
    }

    private static boolean atlasLogged;

    private static void updateLightmap() {
        DynamicTexture lightmap = getLightmapTexture();
        if (lightmap == null) {
            return;
        }

        int[] argb = lightmap.getTextureData();
        if (argb == null || argb.length != 16 * 16) {
            return;
        }

        if (lightmapBuffer == null) {
            lightmapBuffer = MemoryUtil.memAlloc(16 * 16 * 4);
        }
        lightmapBuffer.clear();
        // The CPU data is TYPE_INT_ARGB; the Vulkan image is R8G8B8A8, so reorder to RGBA bytes.
        for (int pixel : argb) {
            lightmapBuffer.put((byte) (pixel >> 16));
            lightmapBuffer.put((byte) (pixel >> 8));
            lightmapBuffer.put((byte) pixel);
            lightmapBuffer.put((byte) (pixel >>> 24));
        }
        lightmapBuffer.position(0);
        lightmapBuffer.limit(16 * 16 * 4);

        if (lightmapImage == null) {
            // LINEAR with the +0.5 texel shift applied in the vertex shaders: vanilla
            // never sets a filter on the lightmap (GL default NEAREST), but the Kirino
            // reference samples it LINEAR with the texel position taken as coord*size
            // (no -0.5 correction). Measured on aligned Vulkan/GL pairs: unshifted
            // LINEAR reads 0.885x of the reference at noon, NEAREST 0.88x at night,
            // the shifted LINEAR matches both (median ratio 1.000).
            lightmapImage = new VulkanImage(1, 16, 16, 4, true, true);
        }

        lightmapImage.uploadSubTextureAsync(0, 16, 16, 0, 0, 4, 0, 0, 16, lightmapBuffer);
        VTextureSelector.setLightTexture(lightmapImage);
        if (!lightmapLogged) {
            lightmapLogged = true;
            Initializer.LOGGER.info("[texture] lightmap uploaded (CPU path)");
        }
    }

    private static boolean lightmapLogged;

    /** Cached CPU mirror of the lightmap; {@code DynamicTexture} keeps one array instance. */
    private static int[] lightmapData;

    private static int[] lightmapData() {
        if (lightmapData == null) {
            DynamicTexture lightmap = getLightmapTexture();
            if (lightmap == null) {
                return null;
            }
            int[] argb = lightmap.getTextureData();
            if (argb == null || argb.length != 16 * 16) {
                return null;
            }
            lightmapData = argb;
        }
        return lightmapData;
    }

    /**
     * Multiplies {@code rgb} by the lightmap entry vanilla would modulate a batch with for
     * this block/sky light pair. Old-model entities carry no lightmap attribute: the fixed
     * function path samples one constant coordinate per entity (the lightmap unit's current
     * texture coordinate, set by {@code RenderManager.renderEntityStatic}), so folding the
     * sample into the shader colour reproduces it exactly.
     */
    public static void multiplyLightmap(int block, int sky, float[] rgb) {
        int[] argb = lightmapData();
        if (argb == null) {
            return;
        }
        int pixel = argb[(sky & 15) * 16 + (block & 15)];
        rgb[0] *= ((pixel >> 16) & 255) / 255.0f;
        rgb[1] *= ((pixel >> 8) & 255) / 255.0f;
        rgb[2] *= (pixel & 255) / 255.0f;
    }

    /** Debug helper: writes the current Vulkan atlas/lightmap images out as PNGs. */
    public static void dumpDebug(java.io.File dir) {
        if (atlasImage != null) {
            atlasImage.dumpToPng(new java.io.File(dir, "vm_atlas.png"));
        }
        if (lightmapImage != null) {
            lightmapImage.dumpToPng(new java.io.File(dir, "vm_lightmap.png"));
        }
    }

    /** Rebinds the block atlas to Sampler0 after other passes borrowed the binding. */
    public static void restoreAtlasBinding() {
        if (atlasImage != null) {
            VTextureSelector.bindTexture(atlasImage);
        }
    }

    /**
     * The mirrored block atlas and the GL id it mirrors. Item draws (dropped items, the
     * first-person hand, GUI icons) bind the atlas too; binding the mirror avoids both a
     * 16 MB {@code glGetTexImage} snapshot and a VRAM duplicate, and keeps animated item
     * tiles (water, lava, portals) animating.
     */
    public static VulkanImage getAtlasImage() {
        return atlasImage;
    }

    public static int getAtlasGlId() {
        return atlasGlId;
    }

    private static DynamicTexture getLightmapTexture() {
        try {
            EntityRenderer entityRenderer = Minecraft.getMinecraft().entityRenderer;
            if (entityRenderer == null) {
                return null;
            }
            if (lightmapField == null) {
                lightmapField = EntityRenderer.class.getDeclaredField("lightmapTexture");
                lightmapField.setAccessible(true);
            }
            Object texture = lightmapField.get(entityRenderer);
            return texture instanceof DynamicTexture ? (DynamicTexture) texture : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private TextureBridge() {
    }
}
