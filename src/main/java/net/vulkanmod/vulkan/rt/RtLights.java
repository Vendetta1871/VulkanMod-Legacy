package net.vulkanmod.vulkan.rt;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.vulkanmod.Initializer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Light-emitting blocks of the ray-traced sections, for the RT block lighting pass.
 * Each section's emitters are scanned when its BLAS is built. The GPU gets a light list
 * (position relative to {@link #originX}.., level) and a hashed grid of 8-block cells,
 * each listing the lights whose range (their level, in blocks) reaches it.
 */
public final class RtLights {

    static final int CELL = 8, MAX_LIGHTS = 16384, MAX_PER_CELL = 255;
    private static final int EMPTY = Integer.MIN_VALUE;

    private static boolean dirty = true;
    private static RtBuffer lights, grid, index;
    private static int gridMask, lightCount, lastHash;
    private static int originX, originY, originZ;
    private static final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private RtLights() {}

    /**
     * Light color per block (registry path); others get {@link #DEFAULT_COLOR}. Vanilla has
     * no light colors: these follow what the blocks look like.
     */
    private static final java.util.Map<String, float[]> COLORS = new HashMap<>();
    private static final float[] DEFAULT_COLOR = {1.0f, 0.86f, 0.66f};
    /** Blocks that glow for RT although vanilla gives them no light: path -> level. */
    private static final java.util.Map<String, Integer> EXTRA_EMITTERS = new HashMap<>();
    private static final java.util.List<float[]> colorTable = new ArrayList<>();
    private static final java.util.Map<String, Integer> colorIndex = new HashMap<>();

    static {
        color(1.0f, 0.82f, 0.55f, "torch", "lit_pumpkin", "lit_furnace", "fire");
        color(1.0f, 0.18f, 0.10f, "redstone_torch", "lit_redstone_ore", "redstone_block", "powered_repeater", "powered_comparator");
        color(1.0f, 0.88f, 0.62f, "glowstone", "lit_redstone_lamp");
        color(1.0f, 0.45f, 0.12f, "lava", "flowing_lava", "magma", "lava_bucket", "blaze_rod", "blaze_powder", "magma_cream");
        color(1.0f, 0.88f, 0.62f, "glowstone_dust");
        color(0.85f, 0.85f, 1.0f, "nether_star");
        color(0.78f, 0.92f, 1.0f, "sea_lantern", "beacon");
        color(0.95f, 0.92f, 1.0f, "end_rod");
        color(0.62f, 0.25f, 1.0f, "portal", "end_portal", "end_gateway", "ender_chest");
        color(0.45f, 1.0f, 0.55f, "brewing_stand");
        EXTRA_EMITTERS.put("redstone_block", 7);
    }

    /** Saturated colors are lifted to a common luminance, so a red light is not a dim one. */
    private static void color(float r, float g, float b, String... blocks) {
        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        float k = Math.max(1.0f, 0.8f / lum);
        for (String block : blocks) COLORS.put(block, new float[]{r * k, g * k, b * k});
    }

    /** The light color of a block or item (registry path), also for dynamic lights. */
    public static float[] colorFor(String path) {
        return COLORS.getOrDefault(path, DEFAULT_COLOR);
    }

    /** Index into the color table (created on first use; stable for the session). */
    private static int colorOf(String path) {
        Integer i = colorIndex.get(path);
        if (i == null) {
            i = colorTable.size();
            colorTable.add(COLORS.getOrDefault(path, DEFAULT_COLOR));
            colorIndex.put(path, i);
        }
        return i;
    }

    /** Packs the section's emitters as x | y << 4 | z << 8 | level << 12 | color << 16, or null when none. */
    static int[] scan(RtScene.Section s) {
        World world = Minecraft.getMinecraft().world;
        int cy = s.originY >> 4;
        if (world == null || cy < 0 || cy > 15) return null;
        ExtendedBlockStorage storage = world.getChunk(s.originX >> 4, s.originZ >> 4).getBlockStorageArray()[cy];
        if (storage == null || storage.isEmpty()) return null;
        int[] out = null;
        int n = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    IBlockState state = storage.get(x, y, z);
                    if (state.getBlock() == Blocks.AIR) continue;
                    int level = state.getLightValue(world, pos.setPos(s.originX + x, s.originY + y, s.originZ + z));
                    // Only emitters (and the extra ones) pay for the registry lookup.
                    if (level <= 0 && state.getBlock() != Blocks.REDSTONE_BLOCK) continue;
                    net.minecraft.util.ResourceLocation id = state.getBlock().getRegistryName();
                    String path = id == null ? "" : id.getPath();
                    if (level <= 0) level = EXTRA_EMITTERS.getOrDefault(path, 0);
                    if (level <= 0) continue;
                    if (out == null) out = new int[16];
                    else if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                    out[n++] = x | y << 4 | z << 8 | Math.min(level, 15) << 12 | Math.min(colorOf(path), 0x7FFF) << 16;
                }
            }
        }
        return out == null ? null : java.util.Arrays.copyOf(out, n);
    }

    static void markDirty() { dirty = true; }

    // --- dynamic lights (held / dropped light sources, DynamicLights in "on" mode) ---

    private static RtBuffer dynamic;
    private static int dynamicCount;
    private static ByteBuffer dynamicCpu;

    public static long dynamicBuffer() { return dynamic.buffer; }
    public static long dynamicBytes() { return dynamic.size; }
    public static int dynamicCount() { return dynamicCount; }

    /** Uploads this frame's dynamic lights (same layout as the static list). Every frame. */
    static void updateDynamic(VkCommandBuffer cmd, MemoryStack stack, double camX, double camY, double camZ) {
        int max = net.vulkanmod.render.DynamicLights.MAX;
        if (dynamic == null) {
            dynamic = RtBuffer.create(max * 32L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            dynamicCpu = MemoryUtil.memCalloc(max * 32);
        }
        java.util.List<net.vulkanmod.render.DynamicLights.Light> list =
                net.vulkanmod.render.DynamicLights.mode().equals("on")
                        ? net.vulkanmod.render.DynamicLights.lights() : java.util.List.of();
        float ox = (float) (camX - originX), oy = (float) (camY - originY), oz = (float) (camZ - originZ);
        int n = Math.min(max, list.size());
        for (int i = 0; i < n; i++) {
            net.vulkanmod.render.DynamicLights.Light l = list.get(i);
            int o = i * 32;
            dynamicCpu.putFloat(o, l.x() + ox).putFloat(o + 4, l.y() + oy).putFloat(o + 8, l.z() + oz)
                    .putFloat(o + 12, l.level())
                    .putFloat(o + 16, l.color()[0]).putFloat(o + 20, l.color()[1]).putFloat(o + 24, l.color()[2])
                    .putFloat(o + 28, 0.0f);
        }
        dynamicCount = n;
        if (n == 0) return;
        org.lwjgl.vulkan.VkMemoryBarrier2.Buffer b = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack);
        // The previous frame's traces read the single buffer.
        b.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR).srcAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT);
        vkCmdPipelineBarrier2(cmd, org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
        RtScene.updateBuffer(cmd, dynamic.buffer, dynamicCpu, n * 32);
        b.get(0).srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR).dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        vkCmdPipelineBarrier2(cmd, org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
    }

    public static boolean ready() { return lights != null; }
    public static int count() { return lightCount; }
    /** Bumped whenever the light list is re-uploaded (light indices change). */
    public static int generation() { return generation; }
    private static int generation;
    public static int gridMask() { return gridMask; }
    public static long lightBuffer() { return lights.buffer; }
    public static long lightBytes() { return lights.size; }
    public static long gridBuffer() { return grid.buffer; }
    public static long gridBytes() { return grid.size; }
    public static long indexBuffer() { return index.buffer; }
    public static long indexBytes() { return index.size; }

    /** Camera position relative to the light origin (the lights are stored relative to it). */
    public static float[] cameraOffset(double camX, double camY, double camZ) {
        return new float[]{(float) (camX - originX), (float) (camY - originY), (float) (camZ - originZ)};
    }

    /**
     * Rebuilds and uploads the light data when emitters changed or the camera moved far
     * from the origin (float precision). Outside rendering, before the RT passes.
     */
    static void update(VkCommandBuffer cmd, List<RtScene.Section> sections, double camX, double camY, double camZ) {
        boolean far = Math.abs(camX - originX) > 1024 || Math.abs(camZ - originZ) > 1024 || Math.abs(camY - originY) > 1024;
        if (!dirty && !far && lights != null) return;
        dirty = false;
        if (far || lights == null) {
            originX = (int) Math.floor(camX) & ~(CELL - 1);
            originY = (int) Math.floor(camY) & ~(CELL - 1);
            originZ = (int) Math.floor(camZ) & ~(CELL - 1);
        }

        // Light list, nearest sections first when over the cap.
        ArrayList<int[]> sorted = new ArrayList<>();
        // A section has one RtScene.Section per render layer, each scanned: keep one.
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (RtScene.Section s : sections) {
            if (s.lights == null || !s.placed) continue;
            if (!seen.add(((long) (s.originX >> 4) & 0x3FFFFF) << 42 | ((long) (s.originY >> 4) & 0xFFFFF) << 22
                    | ((long) (s.originZ >> 4) & 0x3FFFFF))) continue;
            long dx = s.originX + 8 - (long) camX, dy = s.originY + 8 - (long) camY, dz = s.originZ + 8 - (long) camZ;
            sorted.add(new int[]{(int) Math.min(Integer.MAX_VALUE, dx * dx + dy * dy + dz * dz), s.index});
        }
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));

        // Per light: center (relative to the origin), level, then rgb color.
        float[] lx = new float[MAX_LIGHTS * 8];
        int n = 0;
        outer:
        for (int[] e : sorted) {
            RtScene.Section s = sections.get(e[1]);
            for (int packed : s.lights) {
                if (n == MAX_LIGHTS) break outer;
                float[] rgb = colorTable.get(packed >>> 16);
                lx[n * 8] = s.originX + (packed & 15) - originX + 0.5f;
                lx[n * 8 + 1] = s.originY + (packed >> 4 & 15) - originY + 0.5f;
                lx[n * 8 + 2] = s.originZ + (packed >> 8 & 15) - originZ + 0.5f;
                lx[n * 8 + 3] = packed >> 12 & 15;
                lx[n * 8 + 4] = rgb[0];
                lx[n * 8 + 5] = rgb[1];
                lx[n * 8 + 6] = rgb[2];
                n++;
            }
        }
        // Chunk rebuilds without emitter changes (most of them) keep the uploaded data.
        int hash = n;
        for (int i = 0; i < n * 8; i++) hash = hash * 31 + Float.floatToIntBits(lx[i]);
        if (!far && lights != null && hash == lastHash && n == lightCount) return;
        lastHash = hash;
        lightCount = n;
        generation++;

        // Cells reached by each light's range.
        HashMap<Long, int[]> cells = new HashMap<>();
        for (int i = 0; i < n; i++) {
            float x = lx[i * 8], y = lx[i * 8 + 1], z = lx[i * 8 + 2], r = lx[i * 8 + 3];
            int x0 = floorDiv(x - r), x1 = floorDiv(x + r);
            int y0 = floorDiv(y - r), y1 = floorDiv(y + r);
            int z0 = floorDiv(z - r), z1 = floorDiv(z + r);
            for (int cx = x0; cx <= x1; cx++)
                for (int cy = y0; cy <= y1; cy++)
                    for (int cz = z0; cz <= z1; cz++) {
                        // Skip cells the light sphere misses.
                        float qx = clampF(x, cx * CELL, cx * CELL + CELL) - x;
                        float qy = clampF(y, cy * CELL, cy * CELL + CELL) - y;
                        float qz = clampF(z, cz * CELL, cz * CELL + CELL) - z;
                        if (qx * qx + qy * qy + qz * qz > r * r) continue;
                        int[] list = cells.computeIfAbsent(key(cx, cy, cz), k -> new int[9]);
                        int c = list[0];
                        if (c == MAX_PER_CELL) continue;
                        if (c + 1 == list.length) {
                            list = java.util.Arrays.copyOf(list, Math.min(list.length * 2, MAX_PER_CELL + 1));
                            cells.put(key(cx, cy, cz), list);
                        }
                        list[c + 1] = i;
                        list[0] = c + 1;
                    }
        }

        int tableSize = 64;
        while (tableSize < cells.size() * 2) tableSize <<= 1;
        gridMask = tableSize - 1;
        int[] table = new int[tableSize * 4];
        for (int i = 0; i < tableSize; i++) table[i * 4] = EMPTY;
        int indexCount = 0;
        for (int[] list : cells.values()) indexCount += list[0];
        int[] indices = new int[Math.max(1, indexCount)];
        int at = 0;
        for (var e : cells.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 42), cy = (int) (k << 22 >> 43), cz = (int) (k << 43 >> 43);
            int[] list = e.getValue();
            int slot = hash(cx, cy, cz) & gridMask;
            while (table[slot * 4] != EMPTY) slot = (slot + 1) & gridMask;
            table[slot * 4] = cx;
            table[slot * 4 + 1] = cy;
            table[slot * 4 + 2] = cz;
            table[slot * 4 + 3] = at | list[0] << 24;
            System.arraycopy(list, 1, indices, at, list[0]);
            at += list[0];
        }

        release();
        lights = upload(cmd, floatsToBytes(lx, Math.max(1, n) * 8));
        grid = upload(cmd, intsToBytes(table, table.length));
        index = upload(cmd, intsToBytes(indices, indices.length));

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkMemoryBarrier2.Buffer b = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack);
            b.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR).dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            vkCmdPipelineBarrier2(cmd, org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
        }
        if (RtScene.LOG) {
            Initializer.LOGGER.info("[rt] lights: {} in {} cells ({} refs)", n, cells.size(), indexCount);
        }
    }

    private static RtBuffer upload(VkCommandBuffer cmd, ByteBuffer data) {
        int bytes = data.remaining();
        RtBuffer b = RtBuffer.create(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
        RtScene.updateBuffer(cmd, b.buffer, data, bytes);
        MemoryUtil.memFree(data);
        return b;
    }

    private static ByteBuffer floatsToBytes(float[] v, int count) {
        ByteBuffer b = MemoryUtil.memAlloc(count * 4);
        for (int i = 0; i < count; i++) b.putFloat(i * 4, v[i]);
        return b;
    }

    private static ByteBuffer intsToBytes(int[] v, int count) {
        ByteBuffer b = MemoryUtil.memAlloc(count * 4);
        for (int i = 0; i < count; i++) b.putInt(i * 4, v[i]);
        return b;
    }

    /** Must match lighting.rgen. */
    private static int hash(int x, int y, int z) {
        return x * 73856093 ^ y * 19349663 ^ z * 83492791;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
    }

    private static int floorDiv(float v) {
        return (int) Math.floor(v / CELL);
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Old buffers outlive the frames still reading them. */
    private static void release() {
        for (RtBuffer b : new RtBuffer[]{lights, grid, index}) {
            if (b != null) RtBuffer.defer(b::destroyNow);
        }
        lights = grid = index = null;
    }

    static void destroy() {
        if (dynamic != null) {
            dynamic.destroyNow();
            dynamic = null;
            MemoryUtil.memFree(dynamicCpu);
            dynamicCpu = null;
        }
        dynamicCount = 0;
        if (lights != null) lights.destroyNow();
        if (grid != null) grid.destroyNow();
        if (index != null) index.destroyNow();
        lights = grid = index = null;
        lightCount = 0;
        dirty = true;
    }
}
