package net.vulkanmod.render;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.rt.RtLights;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic lights: light-emitting items held by players and mobs or lying on the ground, and
 * burning entities. Collected once per frame (up to {@link #MAX} nearest). Config
 * rtDynamicLights: "fast" raises the block light of terrain vertices (and entities) near
 * them in the raster shaders, like Sodium's dynamic lights but without chunk rebuilds;
 * "on" adds them to the ray-traced block lights (RtLights) with shadows.
 */
public final class DynamicLights {

    public static final int MAX = 16;
    private static final double RANGE = 48.0;

    /** Item light levels for items that are not blocks (registry path). */
    private static final Map<String, Integer> ITEM_LEVELS = new HashMap<>();

    static {
        ITEM_LEVELS.put("lava_bucket", 15);
        ITEM_LEVELS.put("blaze_rod", 10);
        ITEM_LEVELS.put("blaze_powder", 8);
        ITEM_LEVELS.put("glowstone_dust", 10);
        ITEM_LEVELS.put("magma_cream", 6);
        ITEM_LEVELS.put("nether_star", 12);
    }

    /** A light this frame: camera-relative position, level 0..15, color. */
    public record Light(float x, float y, float z, int level, float[] color) {}

    private static final ArrayList<Light> lights = new ArrayList<>();
    private static long collectedFrame = -1;
    /** View-space position and level per light for the raster ("fast") path. */
    private static final float[] viewSpace = new float[MAX * 4];
    private static final Vector4f v = new Vector4f();

    private DynamicLights() {}

    /** "off" | "fast" | "on" as configured; "on" needs ray tracing, else it is "fast". */
    public static String mode() {
        String m = Initializer.CONFIG.effectiveDynamicLights();
        if (m.equals("on") && !net.vulkanmod.vulkan.rt.RtScene.enabled()) return "fast";
        return m;
    }

    /** This frame's lights (collected on first use per frame, client thread). */
    public static ArrayList<Light> lights() {
        long frame = net.vulkanmod.vulkan.Drawer.frameCounter();
        if (frame != collectedFrame) {
            collectedFrame = frame;
            collect();
        }
        return lights;
    }

    private static void collect() {
        lights.clear();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mode().equals("off") || !CameraHistory.valid()) return;
        float pt = mc.getRenderPartialTicks();
        double cx = CameraHistory.camX(), cy = CameraHistory.camY(), cz = CameraHistory.camZ();
        ArrayList<float[]> found = new ArrayList<>();
        for (Entity e : mc.world.loadedEntityList) {
            double ex = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
            double ey = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt;
            double ez = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
            double dx = ex - cx, dz = ez - cz;
            if (dx * dx + dz * dz > RANGE * RANGE) continue;
            int level = 0;
            String path = null;
            double y = ey;
            if (e instanceof EntityItem item) {
                ItemStack stack = item.getItem();
                level = levelOf(stack);
                path = pathOf(stack);
                y += 0.3;
            } else if (e instanceof EntityLivingBase living) {
                for (ItemStack stack : new ItemStack[]{living.getHeldItemMainhand(), living.getHeldItemOffhand()}) {
                    int l = levelOf(stack);
                    if (l > level) {
                        level = l;
                        path = pathOf(stack);
                    }
                }
                y += e.getEyeHeight() * 0.75;
            }
            if (e.isBurning() && level < 12) {
                level = 12;
                path = "fire";
                y = ey + e.height * 0.5;
            }
            if (level <= 0) continue;
            float[] color = RtLights.colorFor(path == null ? "" : path);
            found.add(new float[]{(float) dx, (float) (y - cy), (float) dz, level, color[0], color[1], color[2]});
        }
        found.sort((a, b) -> Float.compare(a[0] * a[0] + a[1] * a[1] + a[2] * a[2], b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
        for (int i = 0; i < Math.min(MAX, found.size()); i++) {
            float[] f = found.get(i);
            lights.add(new Light(f[0], f[1], f[2], (int) f[3], new float[]{f[4], f[5], f[6]}));
        }
    }

    private static int levelOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.getItem() instanceof ItemBlock ib) {
            Block block = ib.getBlock();
            @SuppressWarnings("deprecation")
            int l = block.getStateFromMeta(stack.getMetadata()).getLightValue();
            return l;
        }
        return ITEM_LEVELS.getOrDefault(pathOf(stack), 0);
    }

    private static String pathOf(ItemStack stack) {
        ResourceLocation id = stack.getItem().getRegistryName();
        return id == null ? "" : id.getPath();
    }

    /**
     * For the raster ("fast") path: up to {@link #MAX} vec4 (view-space position, level; level
     * 0 unused). Empty unless the mode is "fast".
     */
    public static float[] viewSpaceArray() {
        return fill(mode().equals("fast"));
    }

    /**
     * As {@link #viewSpaceArray()} but in both "fast" and "on": for what ray tracing does not
     * relight (particles, drawn after the RT lighting).
     */
    public static float[] viewSpaceArrayAll() {
        return fill(!mode().equals("off"));
    }

    private static float[] fill(boolean enabled) {
        java.util.Arrays.fill(viewSpace, 0.0f);
        if (!enabled) return viewSpace;
        ArrayList<Light> list = lights();
        org.joml.Matrix4f view = CameraHistory.view();
        for (int i = 0; i < list.size(); i++) {
            Light l = list.get(i);
            v.set(l.x(), l.y(), l.z(), 1.0f);
            view.transform(v);
            viewSpace[i * 4] = v.x;
            viewSpace[i * 4 + 1] = v.y;
            viewSpace[i * 4 + 2] = v.z;
            viewSpace[i * 4 + 3] = l.level();
        }
        return viewSpace;
    }

    /** The strongest dynamic block light level at a camera-relative point (entity lighting). */
    public static int levelAt(double x, double y, double z) {
        if (mode().equals("off")) return 0;
        int best = 0;
        for (Light l : lights()) {
            double dx = x - l.x(), dy = y - l.y(), dz = z - l.z();
            int level = (int) (l.level() - Math.sqrt(dx * dx + dy * dy + dz * dz));
            best = Math.max(best, level);
        }
        return best;
    }
}
