package net.vulkanmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureMap;
import net.vulkanmod.Initializer;
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
 * directly (no GL round-trip) once per frame. The block atlas has no CPU mirror, so it
 * is downloaded with {@code glGetTexImage} — but only when its contents actually change
 * (an animated sprite advanced a frame, or the atlas was re-stitched), never per frame:
 * {@code glGetTexImage} forces a full GPU pipeline flush on Mesa and is far too slow to
 * run every frame.</p>
 */
public class TextureBridge {

    private static VulkanImage atlasImage;
    private static VulkanImage lightmapImage;

    private static boolean atlasDirty = true;
    private static int atlasWidth = -1;
    private static int atlasHeight = -1;

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
