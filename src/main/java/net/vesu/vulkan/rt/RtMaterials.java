package net.vesu.vulkan.rt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.vesu.Initializer;
import net.vesu.render.TextureBridge;
import net.vesu.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Material atlas for the RT passes: the block atlas layout with LabPBR specular data per
 * texel (R: perceptual smoothness, G: F0 reflectance, 230..255 = metal, B: porosity/SSS,
 * A: emission 0..254, 255 = none) and LabPBR normals: a resource pack's {@code <texture>_s.png}
 * / {@code _n.png}, otherwise derived from Vesu's material rules ({@link RtMaterialRules}),
 * otherwise Remix's default material and a flat normal. Rebuilt after every atlas stitch.
 */
public final class RtMaterials {

    /**
     * The material of a sprite without a {@code _s} map, as RTX Remix for Minecraft builds its
     * atlas (BlockTextureManager: roughness 180/255, not metallic, no emission), in LabPBR terms:
     * smoothness s with (1 - s)^2 = 180/255 (pathtrace.rgen reads the roughness Remix's way),
     * F0 0 (dielectric 0.04), alpha 255 (no emission).
     */
    private static final byte DEFAULT_SMOOTHNESS = (byte) Math.round(255.0 * (1.0 - Math.sqrt(180.0 / 255.0)));

    private static boolean dirty = true;
    private static VulkanImage image, normalImage;
    private static VulkanImage builtFor;

    /** R11: sprite materials are gathered off the render thread, one build at a time. */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Vesu RT materials");
                t.setDaemon(true);
                return t;
            });
    private static java.util.concurrent.Future<java.util.List<Rect>> pending;
    private static VulkanImage pendingFor;

    /** One sprite's material texels and its LabPBR normal texels ({@code _n}), tightly packed RGBA8. */
    private record Rect(int x, int y, int w, int h, ByteBuffer pixels, ByteBuffer normals) {}

    private record Sprite(String name, int x, int y, int w, int h) {}

    private RtMaterials() {}

    /** The atlas was restitched (resource reload): rebuild on next use. */
    public static void markDirty() {
        dirty = true;
    }

    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public static void onStitch(net.minecraftforge.client.event.TextureStitchEvent.Post event) {
        if (event.getMap() == Minecraft.getMinecraft().getTextureMapBlocks()) {
            markDirty();
            net.vesu.shader.PbrAtlases.markDirty();
        }
    }

    /**
     * The material atlas matching the current block atlas, or null while it is being
     * built (the passes then read the all-zero {@link #blank()}) or before the atlas exists.
     */
    public static VulkanImage image() {
        VulkanImage atlas = TextureBridge.getAtlasImage();
        if (atlas == null) return null;
        if (dirty || (builtFor != atlas && pendingFor != atlas)) {
            start(atlas);
        }
        if (pending != null && pending.isDone()) {
            finish();
        }
        return builtFor == atlas ? image : null;
    }

    /**
     * The LabPBR normal atlas of the same build ({@code _n}: RG tangent-space normal, DirectX
     * convention; B ambient occlusion; A height), or null while there is none. Sprites without a
     * map hold a flat normal and no occlusion.
     */
    public static VulkanImage normalImage() {
        return builtFor == TextureBridge.getAtlasImage() ? normalImage : null;
    }

    /** Snapshots the sprite layout here, reads the textures on the worker. */
    private static void start(VulkanImage atlas) {
        dirty = false;
        if (pending != null) dropPending();
        Map<String, TextureAtlasSprite> sprites =
                ((net.vesu.mixin.render.TextureMapAccessor) Minecraft.getMinecraft().getTextureMapBlocks())
                        .vesu$getUploadedSprites();
        int w = atlas.getWidth(), h = atlas.getHeight();
        java.util.List<Sprite> layout = new java.util.ArrayList<>(sprites.size());
        for (TextureAtlasSprite sprite : sprites.values()) {
            int x0 = sprite.getOriginX(), y0 = sprite.getOriginY();
            int sw = sprite.getIconWidth(), sh = sprite.getIconHeight();
            if (x0 < 0 || y0 < 0 || x0 + sw > w || y0 + sh > h) continue;
            layout.add(new Sprite(sprite.getIconName(), x0, y0, sw, sh));
        }
        pendingFor = atlas;
        pending = WORKER.submit(() -> gather(layout));
    }

    private static java.util.List<Rect> gather(java.util.List<Sprite> layout) {
        Minecraft mc = Minecraft.getMinecraft();
        java.util.List<Rect> rects = new java.util.ArrayList<>();
        java.util.List<RtMaterialRules.Rule> rules = RtMaterialRules.load(mc);
        try {
            for (Sprite s : layout) {
                ByteBuffer px = MemoryUtil.memCalloc(s.w * s.h * 4);
                ByteBuffer nx = MemoryUtil.memCalloc(s.w * s.h * 4);
                rects.add(new Rect(s.x, s.y, s.w, s.h, px, nx));
                boolean spec = fromPack(mc, s.name, "_s", px, s.w, s.h);
                boolean normals = fromPack(mc, s.name, "_n", nx, s.w, s.h);
                if (spec && normals) continue;
                // A map the resources lack: derived from Vesu's material rules and the texture.
                RtMaterialRules.Rule rule = RtMaterialRules.find(rules, new ResourceLocation(s.name).getPath());
                ByteBuffer albedo = rule != null ? MemoryUtil.memCalloc(s.w * s.h * 4) : null;
                if (albedo != null && fromPack(mc, s.name, "", albedo, s.w, s.h)) {
                    ByteBuffer stone = null;
                    if ("ore".equals(rule.metalMask)) {
                        stone = MemoryUtil.memCalloc(s.w * s.h * 4);
                        if (!fromPack(mc, "minecraft:blocks/stone", "", stone, s.w, s.h)) {
                            MemoryUtil.memFree(stone);
                            stone = null;
                        }
                    }
                    ByteBuffer dspec = MemoryUtil.memCalloc(s.w * s.h * 4);
                    ByteBuffer dnrm = MemoryUtil.memCalloc(s.w * s.h * 4);
                    RtMaterialRules.derive(rule, albedo, stone, s.w, s.h,
                            (DEFAULT_SMOOTHNESS & 0xFF) / 255.0f, dspec, dnrm);
                    if (!spec) MemoryUtil.memCopy(dspec, px);
                    if (!normals) MemoryUtil.memCopy(dnrm, nx);
                    MemoryUtil.memFree(dspec);
                    MemoryUtil.memFree(dnrm);
                    if (stone != null) MemoryUtil.memFree(stone);
                } else {
                    if (!spec) fromDefaults(px, s.w, s.h);
                    if (!normals) flatNormals(nx, s.w, s.h);
                }
                if (albedo != null) MemoryUtil.memFree(albedo);
            }
        } catch (RuntimeException | LinkageError e) {
            freeAll(rects);
            throw e;
        }
        return rects;
    }

    /** Uploads a finished build into a new image that replaces the current one. */
    private static void finish() {
        VulkanImage atlas = pendingFor;
        java.util.List<Rect> rects;
        try {
            rects = pending.get();
        } catch (Exception e) {
            Initializer.LOGGER.error("[rt] material atlas failed", e);
            pending = null;
            pendingFor = null;
            return;
        }
        pending = null;
        pendingFor = null;
        if (atlas != TextureBridge.getAtlasImage() || atlas.isFreed()) {
            freeAll(rects);
            return;
        }
        int w = atlas.getWidth(), h = atlas.getHeight();
        try {
            VulkanImage next = new VulkanImage(1, w, h, 4, false, true);
            VulkanImage nextNormals = new VulkanImage(1, w, h, 4, false, true);
            next.beginTileBatch();
            next.clearInBatch();
            for (Rect r : rects) {
                next.uploadSubTextureAsync(0, r.w, r.h, r.x, r.y, 4, 0, 0, r.w, r.pixels);
            }
            next.endTileBatch();
            nextNormals.beginTileBatch();
            nextNormals.clearInBatch();
            for (Rect r : rects) {
                nextNormals.uploadSubTextureAsync(0, r.w, r.h, r.x, r.y, 4, 0, 0, r.w, r.normals);
            }
            nextNormals.endTileBatch();
            if (image != null) image.free();
            if (normalImage != null) normalImage.free();
            image = next;
            normalImage = nextNormals;
            builtFor = atlas;
            Initializer.LOGGER.info("[rt] materials: {}x{}, {} sprites", w, h, rects.size());
        } catch (Exception | LinkageError e) {
            Initializer.LOGGER.error("[rt] material atlas upload failed", e);
        } finally {
            freeAll(rects);
        }
    }

    private static void dropPending() {
        java.util.concurrent.Future<java.util.List<Rect>> f = pending;
        pending = null;
        pendingFor = null;
        // The worker is single-threaded, so this runs after that build and frees its result.
        WORKER.execute(() -> {
            try {
                freeAll(f.get());
            } catch (Exception ignored) {
                // Failed: nothing was returned.
            }
        });
    }

    private static void freeAll(java.util.List<Rect> rects) {
        for (Rect r : rects) {
            MemoryUtil.memFree(r.pixels);
            MemoryUtil.memFree(r.normals);
        }
    }

    /** Copies {@code <texture><suffix>.png} (nearest-scaled to the sprite, first animation frame). */
    private static boolean fromPack(Minecraft mc, String iconName, String suffix, ByteBuffer out, int sw, int sh) {
        ResourceLocation name = new ResourceLocation(iconName);
        ResourceLocation map = new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + suffix + ".png");
        try (IResource res = mc.getResourceManager().getResource(map)) {
            BufferedImage img = TextureUtil.readBufferedImage(res.getInputStream());
            int iw = img.getWidth();
            int ih = Math.min(img.getHeight(), Math.max(1, iw * sh / sw));
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    int argb = img.getRGB(x * iw / sw, y * ih / sh);
                    int o = (y * sw + x) * 4;
                    out.put(o, (byte) (argb >> 16)).put(o + 1, (byte) (argb >> 8))
                            .put(o + 2, (byte) argb).put(o + 3, (byte) (argb >>> 24));
                }
            }
            return true;
        } catch (java.io.IOException e) {
            return false;
        } catch (RuntimeException e) {
            // A broken map (unreadable, null image) only loses this sprite's material.
            Initializer.LOGGER.warn("[rt] skipping material {}: {}", map, e.toString());
            return false;
        }
    }

    /** A flat LabPBR normal (0.5, 0.5), no occlusion, no height. */
    private static void flatNormals(ByteBuffer out, int sw, int sh) {
        for (int i = 0; i < sw * sh; i++) {
            out.put(i * 4, (byte) 128).put(i * 4 + 1, (byte) 128).put(i * 4 + 2, (byte) 255).put(i * 4 + 3, (byte) 255);
        }
    }

    private static void fromDefaults(ByteBuffer out, int sw, int sh) {
        for (int i = 0; i < sw * sh; i++) {
            out.put(i * 4, DEFAULT_SMOOTHNESS).put(i * 4 + 1, (byte) 0).put(i * 4 + 2, (byte) 0).put(i * 4 + 3, (byte) 255);
        }
    }

    private static VulkanImage blank, blankNormal;

    /** A 1x1 flat normal without occlusion: the fallback of the normal atlas. */
    public static VulkanImage blankNormal() {
        if (blankNormal == null) {
            blankNormal = new VulkanImage(1, 1, 1, 4, false, true);
            ByteBuffer flat = MemoryUtil.memCalloc(4);
            flatNormals(flat, 1, 1);
            blankNormal.uploadSubTextureAsync(0, 1, 1, 0, 0, 4, 0, 0, 1, flat);
            MemoryUtil.memFree(flat);
        }
        return blankNormal;
    }

    /** A 1x1 all-zero material: the fallback when the atlas could not be built. */
    public static VulkanImage blank() {
        if (blank == null) {
            blank = new VulkanImage(1, 1, 1, 4, false, true);
            ByteBuffer zero = MemoryUtil.memCalloc(4);
            blank.uploadSubTextureAsync(0, 1, 1, 0, 0, 4, 0, 0, 1, zero);
            MemoryUtil.memFree(zero);
        }
        return blank;
    }

    public static void destroy() {
        if (pending != null) dropPending();
        if (blank != null) {
            blank.free();
            blank = null;
        }
        if (image != null) {
            image.free();
            image = null;
        }
        if (normalImage != null) {
            normalImage.free();
            normalImage = null;
        }
        if (blankNormal != null) {
            blankNormal.free();
            blankNormal = null;
        }
        builtFor = null;
        dirty = true;
    }
}
