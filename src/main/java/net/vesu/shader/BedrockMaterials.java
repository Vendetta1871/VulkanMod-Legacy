package net.vesu.shader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.vesu.Initializer;
import net.vesu.pack.source.PackSource;
import net.vesu.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The texture sets of a Bedrock pack ({@code textures/blocks/<name>.texture_set.json}), decoded
 * to the neutral layout (D11) as sprites of {@link PbrAtlases}: {@code specular} holds perceptual
 * roughness, metalness, emissive and subsurface, {@code normals} the tangent-space normal.
 * Sprite names follow the texture's file name; Bedrock kept most 1.12 names.
 */
public final class BedrockMaterials {

    private BedrockMaterials() {
    }

    static void load(PackSource src) {
        PbrAtlases.overrides.clear();
        int sets = atlasSets(src, "textures/blocks", "blocks") + atlasSets(src, "textures/items", "items") + actorSets(src, "textures/entity");
        PbrAtlases.markDirty();
        Initializer.LOGGER.info("[shaderpacks] {} texture sets", sets);
    }

    /** Item sprites live in the block atlas on 1.12, so their sets go the way of block sets. */
    private static int atlasSets(PackSource src, String dir, String kind) {
        int sets = 0;
        for (String file : src.list(dir)) {
            if (!file.endsWith(".texture_set.json")) continue;
            String name = file.substring(0, file.length() - ".texture_set.json".length());
            try {
                JsonObject set = JsonParser.parseString(src.read(dir + "/" + file)).getAsJsonObject()
                        .getAsJsonObject("minecraft:texture_set");
                if (decode(src, dir, "minecraft:" + kind + "/" + name, set)) sets++;
                if (kind.equals("blocks")) color(src, name, set);
            } catch (IOException | RuntimeException e) {
                Initializer.LOGGER.warn("[shaderpacks] texture set {}: {}", name, e.toString());
            }
        }
        return sets;
    }

    /** {@code textures/entity/pig/pig.texture_set.json} belongs to the model texture of that path. */
    private static int actorSets(PackSource src, String dir) {
        int sets = 0;
        for (String file : src.list(dir)) {
            if (!file.contains(".")) {
                sets += actorSets(src, dir + "/" + file);
                continue;
            }
            if (!file.endsWith(".texture_set.json")) continue;
            String name = file.substring(0, file.length() - ".texture_set.json".length());
            try {
                JsonObject set = JsonParser.parseString(src.read(dir + "/" + file)).getAsJsonObject()
                        .getAsJsonObject("minecraft:texture_set");
                if (decode(src, dir, "actor:minecraft:" + dir + "/" + name + ".png", set)) sets++;
            } catch (IOException | RuntimeException e) {
                Initializer.LOGGER.warn("[shaderpacks] texture set {}: {}", name, e.toString());
            }
        }
        return sets;
    }

    private static final java.util.List<ByteBuffer> freeList = new java.util.ArrayList<>();

    /** Java draws the side of a grass block as two sprites, Bedrock as one tinted through alpha. */
    private static final java.util.Set<String> KEEP_COLOR = java.util.Set.of("grass_side");
    private static final Map<String, Image> colors = new HashMap<>();
    private static final Map<TextureAtlasSprite, int[][]> applied = new HashMap<>();
    private static VulkanImage appliedTo;

    static void clearColors() {
        colors.clear();
        for (ByteBuffer b : freeList) MemoryUtil.memFree(b);
        freeList.clear();
    }

    private static void color(PackSource src, String name, JsonObject set) throws IOException {
        JsonElement color = set.get("color");
        if (KEEP_COLOR.contains(name) || color == null || !color.isJsonPrimitive() || color.getAsString().startsWith("#")) return;
        Image image = load(src, "textures/blocks", color.getAsString());
        if (image != null) colors.put("minecraft:blocks/" + name, image);
    }

    /**
     * Writes the pack's color textures over their sprites in the block atlas, mips included.
     * The atlas stays the one image everything binds, animations keep running, and the sprites'
     * own data puts the old pixels back. Render thread.
     */
    static void applyColors(VulkanImage atlas) {
        if (atlas == null) return;
        if (atlas != appliedTo) applied.clear();
        appliedTo = atlas;
        restoreColors();
        Map<String, TextureAtlasSprite> sprites =
                ((net.vesu.mixin.render.TextureMapAccessor) Minecraft.getMinecraft().getTextureMapBlocks()).vesu$getUploadedSprites();
        for (TextureAtlasSprite sprite : sprites.values()) {
            Image image = colors.get(sprite.getIconName());
            if (image == null || sprite.hasAnimationMetadata() || sprite.getFrameCount() == 0) continue;
            int[][] old = sprite.getFrameTextureData(0);
            int w = sprite.getIconWidth(), h = sprite.getIconHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) pixels[y * w + x] = image.argb[y * image.h / h * image.w + x * image.w / w];
            }
            int[][] mips = new int[old.length][];
            mips[0] = pixels;
            upload(atlas, sprite, TextureUtil.generateMipmapData(old.length - 1, w, mips));
            applied.put(sprite, old);
        }
    }

    static void restoreColors() {
        if (appliedTo != null) applied.forEach((sprite, old) -> upload(appliedTo, sprite, old));
        applied.clear();
    }

    private static void upload(VulkanImage atlas, TextureAtlasSprite sprite, int[][] mips) {
        for (int level = 0; level < mips.length && mips[level] != null; level++) {
            int w = sprite.getIconWidth() >> level, h = sprite.getIconHeight() >> level;
            if (w == 0 || h == 0) break;
            ByteBuffer out = MemoryUtil.memAlloc(w * h * 4);
            for (int p : mips[level]) out.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
            out.flip();
            atlas.uploadSubTextureAsync(level, w, h, sprite.getOriginX() >> level, sprite.getOriginY() >> level, 4, 0, 0, w, out);
            MemoryUtil.memFree(out);
        }
    }

    private static int rgba(com.google.gson.JsonArray a) {
        int r = a.get(0).getAsInt(), g = a.get(1).getAsInt(), b = a.get(2).getAsInt();
        int alpha = a.size() > 3 ? a.get(3).getAsInt() : 255;
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    private static boolean decode(PackSource src, String dir, String sprite, JsonObject set) throws IOException {
        boolean subsurfaceFormat = set.has("metalness_emissive_roughness_subsurface");
        JsonElement mer = subsurfaceFormat ? set.get("metalness_emissive_roughness_subsurface")
                : set.get("metalness_emissive_roughness");
        boolean any = false;
        if (mer != null) {
            int[] uniform = mer.isJsonPrimitive() && mer.getAsString().startsWith("#") ? new int[]{Integer.parseUnsignedInt(mer.getAsString().substring(1), 16)}
                    : mer.isJsonArray() ? new int[]{rgba(mer.getAsJsonArray())} : null;
            Image image = uniform != null ? new Image(1, 1, uniform) : load(src, dir, mer.getAsString());
            if (image != null) {
                // MER(S): R metalness, G emissive, B roughness, A subsurface. To R roughness, G metalness, B emissive, A subsurface.
                ByteBuffer out = MemoryUtil.memAlloc(image.w * image.h * 4);
                for (int i = 0; i < image.argb.length; i++) {
                    int p = image.argb[i];
                    int metal = p >> 16 & 255, subsurface = subsurfaceFormat ? p >>> 24 : 0;
                    if (subsurface >= metal) metal = 0;
                    else subsurface = 0;
                    out.put((byte) p).put((byte) metal).put((byte) (p >> 8)).put((byte) subsurface);
                }
                out.flip();
                freeList.add(out);
                PbrAtlases.overrides.put(sprite + "|s", new PbrAtlases.Sprite(image.w, image.h, out));
                any = true;
            }
        }
        JsonElement normal = set.get("normal"), height = set.get("heightmap");
        if (normal != null) {
            Image image = load(src, dir, normal.getAsString());
            if (image != null) {
                ByteBuffer out = MemoryUtil.memAlloc(image.w * image.h * 4);
                for (int p : image.argb) out.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) 255);
                out.flip();
                freeList.add(out);
                PbrAtlases.overrides.put(sprite + "|n", new PbrAtlases.Sprite(image.w, image.h, out));
                any = true;
            }
        } else if (height != null && height.isJsonPrimitive() && !height.getAsString().startsWith("#")) {
            Image image = load(src, dir, height.getAsString());
            if (image != null) {
                ByteBuffer out = MemoryUtil.memAlloc(image.w * image.h * 4);
                for (int y = 0; y < image.h; y++) {
                    for (int x = 0; x < image.w; x++) {
                        // A height step of one texel: slope 1 at the full range.
                        float dx = image.height(x + 1, y) - image.height(x - 1, y), dy = image.height(x, y + 1) - image.height(x, y - 1);
                        float l = (float) Math.sqrt(dx * dx + dy * dy + 1.0f);
                        out.put((byte) ((-dx / l * 0.5f + 0.5f) * 255)).put((byte) ((-dy / l * 0.5f + 0.5f) * 255))
                                .put((byte) ((1.0f / l * 0.5f + 0.5f) * 255)).put((byte) (image.height(x, y) * 255));
                    }
                }
                out.flip();
                freeList.add(out);
                PbrAtlases.overrides.put(sprite + "|n", new PbrAtlases.Sprite(image.w, image.h, out));
                any = true;
            }
        }
        return any;
    }

    private record Image(int w, int h, int[] argb) {
        float height(int x, int y) {
            return (argb[Math.floorMod(y, h) * w + Math.floorMod(x, w)] & 255) / 255.0f;
        }
    }

    private static Image load(PackSource src, String dir, String name) throws IOException {
        for (String ext : new String[]{".tga", ".png"}) {
            String path = dir + "/" + name + ext;
            if (!src.exists(path)) continue;
            byte[] bytes = src.readBytes(path);
            if (ext.equals(".tga")) return tga(bytes);
            BufferedImage png = ImageIO.read(new ByteArrayInputStream(bytes));
            if (png == null) return null;
            int[] argb = png.getRGB(0, 0, png.getWidth(), png.getHeight(), null, 0, png.getWidth());
            return new Image(png.getWidth(), png.getHeight(), argb);
        }
        return null;
    }

    /** Uncompressed and run-length TGA, 24 or 32 bits, as the Bedrock tools write them. */
    private static Image tga(byte[] b) throws IOException {
        int idLength = b[0] & 255, type = b[2] & 255;
        int w = (b[12] & 255) | (b[13] & 255) << 8, h = (b[14] & 255) | (b[15] & 255) << 8, bits = b[16] & 255;
        boolean topDown = (b[17] & 0x20) != 0;
        if ((type != 2 && type != 10) || (bits != 24 && bits != 32)) throw new IOException("unsupported TGA type " + type + "/" + bits);
        int bytes = bits / 8, at = 18 + idLength;
        int[] argb = new int[w * h];
        int i = 0;
        while (i < argb.length) {
            int count = 1;
            boolean packed = false;
            if (type == 10) {
                int head = b[at++] & 255;
                packed = head >= 128;
                count = (head & 127) + 1;
            }
            for (int n = 0; n < count && i < argb.length; n++) {
                int p = at;
                argb[i++] = (b[p] & 255) | (b[p + 1] & 255) << 8 | (b[p + 2] & 255) << 16 | (bytes == 4 ? (b[p + 3] & 255) << 24 : 0xFF000000);
                if (!packed) at += bytes;
            }
            if (packed) at += bytes;
        }
        if (!topDown) {
            for (int y = 0; y < h / 2; y++) {
                for (int x = 0; x < w; x++) {
                    int a = y * w + x, c = (h - 1 - y) * w + x, t = argb[a];
                    argb[a] = argb[c];
                    argb[c] = t;
                }
            }
        }
        return new Image(w, h, argb);
    }
}
