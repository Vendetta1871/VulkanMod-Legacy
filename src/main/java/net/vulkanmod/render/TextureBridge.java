package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.mixin.render.EntityRendererAccessor;
import net.vulkanmod.vulkan.TransferQueue;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

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
    // True while the tick's mirrored tiles hold the atlas image in one layout batch (N11).
    private static boolean tileLayoutOpen;

    /**
     * True while the open tile batch belongs to the block atlas. Other TextureMaps
     * (book pages, paintings) also animate sprites through the same upload hook; their
     * tiles must not be mirrored into the block atlas image.
     */
    private static boolean mirrorTarget;

    /** Number of mip levels the mirrored atlas image was created with (vanilla uploads 4). */
    private static int atlasMipLevels = 1;

    /**
     * Opens the shared transfer command buffer for the tile uploads of one animation tick
     * (TextureMapM brackets {@code TextureMap.updateAnimations} with these), so a tick with a
     * dozen animated sprites costs one queue submit instead of a dozen.
     */
    public static void beginTileBatch(boolean blockAtlas) {
        mirrorTarget = blockAtlas && !NO_TILEMIRROR && Initializer.isVulkanActive();
        if (NO_TILEMIRROR || tileBatchOpen || !Initializer.isVulkanActive()) {
            return;
        }
        TransferQueue.startRecording();
        tileBatchOpen = true;
    }

    public static void endTileBatch() {
        mirrorTarget = false;
        // Close the atlas layout batch opened by the first mirrored tile of the tick
        // before the shared command buffer is submitted (N11).
        if (tileLayoutOpen) {
            tileLayoutOpen = false;
            if (atlasImage != null) {
                atlasImage.endTileBatch();
            }
        }
        if (tileBatchOpen) {
            tileBatchOpen = false;
            TransferQueue.endRecording();
        }
    }

    /** Set once per render frame so the lightmap is refreshed at most once per frame. */
    private static boolean updatedThisFrame;

    /** Reusable CPU staging buffer for the 16x16 RGBA lightmap. */
    private static ByteBuffer lightmapBuffer;

    /** Called after the atlas is (re)stitched, see TextureMapM. */
    public static void markAtlasDirty() {
        atlasDirty = true;
    }

    /**
     * Drops the Vulkan mirrors cached against a GL texture whose content or lifetime
     * changed (re-upload under the same id, deletion, id reuse). The mirrors are freed
     * with the in-flight-frame delay inside {@link VulkanImage#free()}.
     */
    public static void invalidateGlTexture(int texId) {
        if (texId <= 0) {
            return;
        }
        // FQN: the net.minecraft.client.renderer.EntityRenderer import shadows ours here.
        net.vulkanmod.render.EntityRenderer.onTextureDeleted(texId);
        GuiRenderer.onTextureDeleted(texId);
    }

    /**
     * Mirrors one animated sprite frame straight into the Vulkan atlas from the CPU pixel
     * data vanilla has just uploaded to GL (see TextureAtlasSpriteM), instead of re-downloading
     * the whole atlas with glGetTexImage. The int[]s are TYPE_INT_ARGB, the same source the GL
     * upload uses, so the mirrored bytes match the GL atlas one-for-one.
     *
     * <p>{@code frames} holds the mip levels vanilla uploads for this frame (4 for a plain
     * frame advance, 1 for the CPU-blended interpolated frames - vanilla leaves the upper
     * levels of those stale too, so mirroring exactly what is uploaded reproduces the GL
     * atlas). Tiles are power-of-two aligned in the atlas, so level i of a tile lands at
     * (x &gt;&gt; i, y &gt;&gt; i) exactly like vanilla's stitched atlas mips.</p>
     */
    public static void uploadSpriteTile(int[][] frames, int width, int height, int x, int y) {
        try {
            if (NO_TILEMIRROR) {
                atlasDirty = true;
                return;
            }
            // Sprite of another atlas (book pages, paintings): GL upload happened, nothing to mirror.
            if (!mirrorTarget) {
                return;
            }
            // Atlas not created yet: fall back to the full download.
            if (atlasImage == null) {
                atlasDirty = true;
                return;
            }
            int levels = Math.min(frames.length, atlasMipLevels);
            if (!tileLayoutOpen) {
                // One SHADER_READ -> TRANSFER_DST -> SHADER_READ round trip per tick
                // instead of one pair per mip copy (N11). Closed in endTileBatch.
                atlasImage.beginTileBatch();
                tileLayoutOpen = true;
            }
            for (int lvl = 0; lvl < levels; lvl++) {
                int[] argb = frames[lvl];
                int lw = width >> lvl, lh = height >> lvl;
                if (argb == null || argb.length != lw * lh) {
                    // Frame data does not match the expected mip size: full download.
                    atlasDirty = true;
                    return;
                }
                int size = lw * lh * 4;
                if (tileBuffer == null || tileBuffer.capacity() < width * height * 4) {
                    if (tileBuffer != null) {
                        MemoryUtil.memFree(tileBuffer);
                    }
                    tileBuffer = MemoryUtil.memAlloc(width * height * 4);
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

                atlasImage.uploadSubTextureAsync(lvl, lw, lh, x >> lvl, y >> lvl, 4, 0, 0, lw, tileBuffer);
            }
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

        // Vanilla uploads 4 mip levels to the atlas; mirror every level the GL texture
        // actually exposes (a GL backend that keeps no mip storage reports level 1 empty
        // and the mirror degrades to the old single-level image).
        int[] levelWidths = new int[4];
        int[] levelHeights = new int[4];
        int levels = 0;
        for (int lvl = 0; lvl < 4; lvl++) {
            levelWidths[lvl] = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, lvl, GL11.GL_TEXTURE_WIDTH);
            levelHeights[lvl] = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, lvl, GL11.GL_TEXTURE_HEIGHT);
            if (levelWidths[lvl] <= 0 || levelHeights[lvl] <= 0) {
                break;
            }
            levels = lvl + 1;
        }

        ByteBuffer[] levelPixels = new ByteBuffer[levels];
        for (int lvl = 0; lvl < levels; lvl++) {
            int size = levelWidths[lvl] * levelHeights[lvl] * 4;
            levelPixels[lvl] = MemoryUtil.memAlloc(size);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, lvl, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, levelPixels[lvl]);
            levelPixels[lvl].position(0);
            levelPixels[lvl].limit(size);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBinding);

        int width = levelWidths[0];
        int height = levelHeights[0];

        if (atlasImage == null || width != atlasWidth || height != atlasHeight || levels != atlasMipLevels) {
            if (atlasImage != null) {
                atlasImage.free();
            }
            // NEAREST filtering (vanilla atlas), clamped addressing, vanilla's mip chain.
            atlasImage = new VulkanImage(levels, width, height, 4, false, true);
            atlasWidth = width;
            atlasHeight = height;
            atlasMipLevels = levels;
        }

        for (int lvl = 0; lvl < levels; lvl++) {
            atlasImage.uploadSubTextureAsync(lvl, levelWidths[lvl], levelHeights[lvl],
                    0, 0, 4, 0, 0, levelWidths[lvl], levelPixels[lvl]);
            MemoryUtil.memFree(levelPixels[lvl]);
        }
        atlasGlId = texId;

        VTextureSelector.bindTexture(atlasImage);
        atlasDirty = false;
        if (!atlasLogged) {
            atlasLogged = true;
            Initializer.LOGGER.info("[texture] atlas uploaded: glId={} {}x{} levels={} id={}", texId, width, height,
                    levels, System.identityHashCode(atlasImage));
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
        EntityRenderer entityRenderer = Minecraft.getMinecraft().entityRenderer;
        // Mixin accessor instead of reflection: the refmap remaps the field name for the
        // SRG runtime, and there is no failed-lookup-throws-every-call path left.
        if (entityRenderer instanceof EntityRendererAccessor accessor) {
            return accessor.vulkanmod$getLightmapTexture();
        }
        return null;
    }

    private TextureBridge() {
    }
}
