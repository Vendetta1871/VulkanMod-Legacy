package net.vulkanmod.vulkan.rt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.TextureBridge;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Material atlas for the RT passes: the block atlas layout with LabPBR specular data per
 * texel (R: perceptual smoothness, G: F0 reflectance, 230..255 = metal, B: porosity/SSS,
 * A: emission). A resource pack's {@code <texture>_s.png} wins; otherwise the built-in
 * defaults below give whole sprites a constant material (metal blocks, polished stone,
 * glass, ice...). Rebuilt after every atlas stitch.
 */
public final class RtMaterials {

    /** LabPBR metal ids (G channel): iron, gold. */
    private static final int IRON = 230, GOLD = 231;

    /** Built-in materials: sprite name (without "blocks/") -> {smoothness, F0}. Prefix match on '*'. */
    private static final Map<String, int[]> DEFAULTS = new HashMap<>();
    private static final Map<String, int[]> PREFIXES = new HashMap<>();

    static {
        def("iron_block", 205, IRON);
        def("gold_block", 215, GOLD);
        def("iron_bars", 170, IRON);
        def("iron_trapdoor", 175, IRON);
        def("door_iron*", 175, IRON);
        def("anvil*", 120, IRON);
        def("cauldron*", 130, IRON);
        def("hopper*", 130, IRON);
        def("rail*", 150, IRON);
        def("diamond_block", 225, 44);
        def("emerald_block", 210, 40);
        def("lapis_block", 120, 12);
        def("redstone_block", 130, 12);
        def("coal_block", 100, 10);
        def("quartz_block*", 175, 12);
        def("quartz_ore", 120, 10);
        def("obsidian", 205, 11);
        def("glass*", 240, 10);
        def("ice", 235, 5);
        def("ice_packed", 220, 5);
        def("glazed_terracotta*", 175, 11);
        def("concrete_*", 110, 10);
        def("prismarine*", 120, 10);
        def("sea_lantern", 160, 10);
        def("purpur*", 95, 10);
        def("slime", 200, 8);
        def("end_bricks", 70, 10);
        def("stone_slab_top", 70, 10);
        def("stonebrick", 40, 10);
    }

    private static void def(String name, int smoothness, int f0) {
        int[] v = {smoothness, f0};
        if (name.endsWith("*")) PREFIXES.put(name.substring(0, name.length() - 1), v);
        else DEFAULTS.put(name, v);
    }

    private static boolean dirty = true;
    private static VulkanImage image;
    private static VulkanImage builtFor;

    private RtMaterials() {}

    /** The atlas was restitched (resource reload): rebuild on next use. */
    public static void markDirty() {
        dirty = true;
    }

    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public static void onStitch(net.minecraftforge.client.event.TextureStitchEvent.Post event) {
        if (event.getMap() == Minecraft.getMinecraft().getTextureMapBlocks()) markDirty();
    }

    /** The material atlas matching the current block atlas, or null before the atlas exists. */
    public static VulkanImage image() {
        VulkanImage atlas = TextureBridge.getAtlasImage();
        if (atlas == null) return null;
        if (dirty || image == null || builtFor != atlas) {
            build(atlas);
        }
        return image;
    }

    private static void build(VulkanImage atlas) {
        dirty = false;
        builtFor = atlas;
        Minecraft mc = Minecraft.getMinecraft();
        TextureMap map = mc.getTextureMapBlocks();
        int w = atlas.getWidth(), h = atlas.getHeight();
        ByteBuffer pixels = MemoryUtil.memCalloc(w * h * 4);
        int packed = 0, defaults = 0;
        try {
            Map<String, TextureAtlasSprite> sprites =
                    ((net.vulkanmod.mixin.render.TextureMapAccessor) map).vulkanmod$getUploadedSprites();
            for (TextureAtlasSprite sprite : sprites.values()) {
                int x0 = sprite.getOriginX(), y0 = sprite.getOriginY();
                int sw = sprite.getIconWidth(), sh = sprite.getIconHeight();
                if (x0 < 0 || y0 < 0 || x0 + sw > w || y0 + sh > h) continue;
                if (fromPack(mc, sprite.getIconName(), pixels, w, x0, y0, sw, sh)) {
                    packed++;
                } else if (fromDefaults(sprite.getIconName(), pixels, w, x0, y0, sw, sh)) {
                    defaults++;
                }
            }
            if (image != null) image.free();
            image = new VulkanImage(1, w, h, 4, false, true);
            image.uploadSubTextureAsync(0, w, h, 0, 0, 4, 0, 0, w, pixels);
            Initializer.LOGGER.info("[rt] materials: {}x{}, {} sprites from packs, {} built-in", w, h, packed, defaults);
        } catch (Exception | LinkageError e) {
            Initializer.LOGGER.error("[rt] material atlas failed", e);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** Copies {@code <texture>_s.png} (nearest-scaled to the sprite, first animation frame). */
    private static boolean fromPack(Minecraft mc, String iconName, ByteBuffer out, int stride,
                                    int x0, int y0, int sw, int sh) {
        ResourceLocation name = new ResourceLocation(iconName);
        ResourceLocation spec = new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + "_s.png");
        try (IResource res = mc.getResourceManager().getResource(spec)) {
            BufferedImage img = TextureUtil.readBufferedImage(res.getInputStream());
            int iw = img.getWidth();
            int ih = Math.min(img.getHeight(), Math.max(1, iw * sh / sw));
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    int argb = img.getRGB(x * iw / sw, y * ih / sh);
                    int o = ((y0 + y) * stride + x0 + x) * 4;
                    out.put(o, (byte) (argb >> 16)).put(o + 1, (byte) (argb >> 8))
                            .put(o + 2, (byte) argb).put(o + 3, (byte) (argb >>> 24));
                }
            }
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static boolean fromDefaults(String iconName, ByteBuffer out, int stride, int x0, int y0, int sw, int sh) {
        String path = new ResourceLocation(iconName).getPath();
        if (!path.startsWith("blocks/")) return false;
        String key = path.substring("blocks/".length());
        int[] v = DEFAULTS.get(key);
        if (v == null) {
            for (Map.Entry<String, int[]> e : PREFIXES.entrySet()) {
                if (key.startsWith(e.getKey())) {
                    v = e.getValue();
                    break;
                }
            }
        }
        if (v == null) return false;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int o = ((y0 + y) * stride + x0 + x) * 4;
                out.put(o, (byte) v[0]).put(o + 1, (byte) v[1]);
            }
        }
        return true;
    }

    public static void destroy() {
        if (image != null) {
            image.free();
            image = null;
        }
        builtFor = null;
        dirty = true;
    }
}
