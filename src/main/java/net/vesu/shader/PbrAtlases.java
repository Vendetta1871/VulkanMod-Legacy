package net.vesu.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.vesu.Initializer;
import net.vesu.render.TextureBridge;
import net.vesu.vulkan.VRenderSystem;
import net.vesu.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@code normals} and {@code specular} atlases of a pack: the block atlas layout, with the
 * raw {@code <texture>_n.png} and {@code <texture>_s.png} of the resource packs, as OptiFine
 * binds them. Bedrock texture sets arrive here as {@link #overrides}. An atlas nothing fills is
 * one texel of the neutral value, so a pack without materials costs nothing.
 */
public final class PbrAtlases {

    private static final int NEUTRAL_NORMAL = 0xFFFF8080, NEUTRAL_SPECULAR = 0;

    /** Sprite pixels from outside the resource packs, RGBA, by {@code <sprite name>|n} and {@code |s}. */
    public record Sprite(int width, int height, ByteBuffer pixels) {
    }

    public static final Map<String, Sprite> overrides = new HashMap<>();

    private static VulkanImage normals, specular, builtFor;
    /** Maps of model textures, whose UVs are their own: one rectangle per texture (Drawer's material block). */
    private static VulkanImage actorNormals, actorSpecular;
    private static final Map<ResourceLocation, float[]> actorRects = new HashMap<>();
    private static final float[] NO_MATERIAL = {0, 0, 0, 0}, BLOCK_ATLAS = {0, 0, -1, 0};
    private static final int ACTOR_ATLAS_WIDTH = 2048;
    /** Model textures are mirrored on demand, so a rectangle follows the GL binding they are mirrored from. */
    private static final Map<Integer, float[]> boundRects = new HashMap<>();
    private static boolean dirty = true;
    private static int generation;

    private PbrAtlases() {
    }

    public static void markDirty() {
        dirty = true;
    }

    public static VulkanImage normals() {
        ensure();
        return normals;
    }

    public static VulkanImage specular() {
        ensure();
        return specular;
    }

    public static VulkanImage actorNormals() {
        ensure();
        return actorNormals;
    }

    public static VulkanImage actorSpecular() {
        ensure();
        return actorSpecular;
    }

    /** Offset and scale into the actor atlas; z 0 for a texture without maps, z below 0 for the block atlas. */
    public static float[] materialRect() {
        int bound = VRenderSystem.trackedBoundTexture;
        if (bound == TextureBridge.getAtlasGlId()) return BLOCK_ATLAS;
        return boundRects.isEmpty() ? NO_MATERIAL : boundRects.getOrDefault(bound, NO_MATERIAL);
    }

    /** From TextureManagerM, once {@code location} is bound. Render thread. */
    public static void noteBound(ResourceLocation location) {
        float[] rect = actorRects.isEmpty() ? null : actorRects.get(location);
        // GL reuses the names of deleted textures: an id is whatever was bound to it last.
        if (rect != null) boundRects.put(VRenderSystem.trackedBoundTexture, rect);
        else if (!boundRects.isEmpty()) boundRects.remove(VRenderSystem.trackedBoundTexture);
    }

    /** Changes when the atlases are rebuilt, which this does if they are out of date. */
    public static int generation() {
        ensure();
        return generation;
    }

    private static void ensure() {
        VulkanImage atlas = TextureBridge.getAtlasImage();
        if (!dirty && atlas == builtFor && normals != null) return;
        dirty = false;
        generation++;
        builtFor = atlas;
        normals = rebuild(normals, atlas, "_n", "n", NEUTRAL_NORMAL);
        specular = rebuild(specular, atlas, "_s", "s", NEUTRAL_SPECULAR);
        buildActors();
        BedrockMaterials.applyColors(atlas);
    }

    /** Shelves of the sets in {@link #overrides} under {@code actor:<location>}, tallest first. */
    private static void buildActors() {
        if (actorNormals != null) actorNormals.free();
        if (actorSpecular != null) actorSpecular.free();
        actorRects.clear();
        boundRects.clear();
        Map<String, int[]> places = new java.util.LinkedHashMap<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        Map<String, int[]> sizes = new HashMap<>();
        for (Map.Entry<String, Sprite> e : overrides.entrySet()) {
            if (!e.getKey().startsWith("actor:")) continue;
            String name = e.getKey().substring("actor:".length(), e.getKey().length() - 2);
            int[] size = sizes.computeIfAbsent(name, k -> new int[2]);
            if (size[0] == 0) names.add(name);
            size[0] = Math.max(size[0], e.getValue().width);
            size[1] = Math.max(size[1], e.getValue().height);
        }
        names.sort(java.util.Comparator.comparingInt((String n) -> -sizes.get(n)[1]).thenComparing(n -> n));
        int x = 0, y = 0, shelf = 0;
        for (String name : names) {
            int[] size = sizes.get(name);
            if (size[0] > ACTOR_ATLAS_WIDTH) continue;
            if (x + size[0] > ACTOR_ATLAS_WIDTH) {
                x = 0;
                y += shelf;
                shelf = 0;
            }
            places.put(name, new int[]{x, y, size[0], size[1]});
            x += size[0];
            shelf = Math.max(shelf, size[1]);
        }
        int w = places.isEmpty() ? 1 : ACTOR_ATLAS_WIDTH, h = Math.max(1, y + shelf);
        actorNormals = actorImage(places, "n", NEUTRAL_NORMAL, w, h);
        actorSpecular = actorImage(places, "s", NEUTRAL_SPECULAR, w, h);
        places.forEach((name, p) -> actorRects.put(new ResourceLocation(name),
                new float[]{(float) p[0] / w, (float) p[1] / h, (float) p[2] / w, (float) p[3] / h}));
        Initializer.LOGGER.info("[shaderpacks] actor atlas: {}x{}, {} textures", w, h, places.size());
    }

    private static VulkanImage actorImage(Map<String, int[]> places, String kind, int neutral, int w, int h) {
        ByteBuffer pixels = filled(w, h, neutral);
        try {
            places.forEach((name, p) -> {
                Sprite sprite = overrides.get("actor:" + name + "|" + kind);
                if (sprite != null) copy(pixels, w, p[0], p[1], p[2], p[3], sprite, null);
            });
            VulkanImage image = new VulkanImage(1, w, h, 4, false, true);
            image.uploadSubTextureAsync(0, w, h, 0, 0, 4, 0, 0, w, pixels);
            return image;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static VulkanImage rebuild(VulkanImage old, VulkanImage atlas, String suffix, String kind, int neutral) {
        if (old != null) old.free();
        ByteBuffer pixels = null;
        int w = 1, h = 1, found = 0;
        try {
            if (atlas != null) {
                w = atlas.getWidth();
                h = atlas.getHeight();
                Minecraft mc = Minecraft.getMinecraft();
                TextureMap map = mc.getTextureMapBlocks();
                Map<String, TextureAtlasSprite> sprites =
                        ((net.vesu.mixin.render.TextureMapAccessor) map).vesu$getUploadedSprites();
                for (TextureAtlasSprite sprite : sprites.values()) {
                    int x0 = sprite.getOriginX(), y0 = sprite.getOriginY(), sw = sprite.getIconWidth(), sh = sprite.getIconHeight();
                    if (x0 < 0 || y0 < 0 || x0 + sw > w || y0 + sh > h) continue;
                    Sprite over = overrides.get(sprite.getIconName() + "|" + kind);
                    BufferedImage img = over == null ? read(mc, sprite.getIconName(), suffix) : null;
                    if (over == null && img == null) continue;
                    if (pixels == null) pixels = filled(w, h, neutral);
                    found++;
                    copy(pixels, w, x0, y0, sw, sh, over, img);
                }
            }
            if (pixels == null) {
                w = h = 1;
                pixels = filled(1, 1, neutral);
            }
            VulkanImage image = new VulkanImage(1, w, h, 4, false, true);
            image.uploadSubTextureAsync(0, w, h, 0, 0, 4, 0, 0, w, pixels);
            Initializer.LOGGER.info("[shaderpacks] {} atlas: {}x{}, {} sprites", kind.equals("n") ? "normals" : "specular", w, h, found);
            return image;
        } finally {
            if (pixels != null) MemoryUtil.memFree(pixels);
        }
    }

    private static ByteBuffer filled(int w, int h, int abgr) {
        ByteBuffer b = MemoryUtil.memAlloc(w * h * 4);
        for (int i = 0; i < w * h; i++) b.putInt(i * 4, abgr);
        return b;
    }

    private static BufferedImage read(Minecraft mc, String iconName, String suffix) {
        ResourceLocation name = new ResourceLocation(iconName);
        ResourceLocation file = new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + suffix + ".png");
        try (IResource res = mc.getResourceManager().getResource(file)) {
            return TextureUtil.readBufferedImage(res.getInputStream());
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            Initializer.LOGGER.warn("[shaderpacks] skipping {}: {}", file, e.toString());
            return null;
        }
    }

    /** Nearest-scaled to the sprite, first animation frame. */
    private static void copy(ByteBuffer out, int stride, int x0, int y0, int sw, int sh, Sprite over, BufferedImage img) {
        int iw = over != null ? over.width : img.getWidth();
        int ih = over != null ? over.height : Math.min(img.getHeight(), Math.max(1, iw * sh / sw));
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int sx = x * iw / sw, sy = y * ih / sh;
                int o = ((y0 + y) * stride + x0 + x) * 4;
                if (over != null) {
                    out.putInt(o, over.pixels.getInt((sy * iw + sx) * 4));
                } else {
                    int argb = img.getRGB(sx, sy);
                    out.put(o, (byte) (argb >> 16)).put(o + 1, (byte) (argb >> 8)).put(o + 2, (byte) argb).put(o + 3, (byte) (argb >>> 24));
                }
            }
        }
    }

    public static void destroy() {
        BedrockMaterials.restoreColors();
        if (normals != null) normals.free();
        if (specular != null) specular.free();
        if (actorNormals != null) actorNormals.free();
        if (actorSpecular != null) actorSpecular.free();
        normals = specular = builtFor = actorNormals = actorSpecular = null;
        actorRects.clear();
        boundRects.clear();
        dirty = true;
    }
}
