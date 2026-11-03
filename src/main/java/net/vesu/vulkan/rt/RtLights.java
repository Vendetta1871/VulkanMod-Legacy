package net.vesu.vulkan.rt;

import net.vesu.vulkan.VkCompat;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.vesu.Initializer;
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
 * each listing the lights whose range (their level, in blocks) reaches it. Both are kept
 * between updates and changed per section (R8).
 */
public final class RtLights {

    static final int CELL = 8, MAX_LIGHTS = 16384, MAX_PER_CELL = 255;
    private static final int EMPTY = Integer.MIN_VALUE;

    private static boolean dirty = true;
    private static RtBuffer lights, grid, index;
    private static int gridMask, lightCount;
    private static int originX, originY, originZ;
    private static final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private static final float[] LIGHT_SCRATCH = new float[MAX_LIGHTS * 8];

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
                    if (enclosed(world, s.originX + x, s.originY + y, s.originZ + z)) continue;
                    if (out == null) out = new int[16];
                    else if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                    out[n++] = x | y << 4 | z << 8 | Math.min(level, 15) << 12 | Math.min(colorOf(path), 0x7FFF) << 16;
                }
            }
        }
        return out == null ? null : java.util.Arrays.copyOf(out, n);
    }

    private static final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * An emitter whose six neighbours all emit too or are opaque cubes (the inside of a
     * lava sea or a glowstone cluster) lights nothing a ray could reach first: skipped,
     * so the light list and grid hold the surfaces.
     */
    private static boolean enclosed(World world, int x, int y, int z) {
        for (int[] d : SIDES) {
            IBlockState n = world.getBlockState(neighbour.setPos(x + d[0], y + d[1], z + d[2]));
            if (n.isOpaqueCube()) continue;
            if (n.getLightValue(world, neighbour) > 0) continue;
            return false;
        }
        return true;
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
        int max = net.vesu.render.DynamicLights.MAX;
        if (dynamic == null) {
            dynamic = RtBuffer.create(max * 32L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            dynamicCpu = MemoryUtil.memCalloc(max * 32);
        }
        java.util.List<net.vesu.render.DynamicLights.Light> list =
                net.vesu.render.DynamicLights.mode().equals("on")
                        ? net.vesu.render.DynamicLights.lights() : java.util.List.of();
        float ox = (float) (camX - originX), oy = (float) (camY - originY), oz = (float) (camZ - originZ);
        int n = Math.min(max, list.size());
        for (int i = 0; i < n; i++) {
            net.vesu.render.DynamicLights.Light l = list.get(i);
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
        VkCompat.pipelineBarrier2(cmd, org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
        RtScene.updateBuffer(cmd, dynamic.buffer, dynamicCpu, n * 32);
        b.get(0).srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR).dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        VkCompat.pipelineBarrier2(cmd, org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
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

    // --- R8: persistent light slots and cell lists ---
    // Each light keeps its slot in the list while its section is unchanged; a changed
    // section only removes and adds its own lights and touches their cells. Freed slots
    // stay in the list with level 0 and are in no cell (the shader reaches lights through
    // cells only). The origin moves (a full reset) when the camera goes far from it.

    /** A section's emitters as scanned (compared to detect changes) and their slots. */
    private record SectionLights(int[] packed, int[] slots) {}

    /** The slots of the lights reaching a cell; {@code trimmed} caches a crowded cell's nearest ones. */
    private static final class Cell {
        int[] slots = new int[8];
        int n;
        int[] trimmed;

        void add(int slot) {
            if (n == slots.length) slots = java.util.Arrays.copyOf(slots, n * 2);
            slots[n++] = slot;
            trimmed = null;
        }
    }

    private static final HashMap<Long, SectionLights> bySection = new HashMap<>();
    private static final HashMap<Long, Cell> cellMap = new HashMap<>();
    private static final int[] freeSlots = new int[MAX_LIGHTS];
    private static int freeTop, highWater;
    private static boolean capWarned;
    private static long tTable;
    private static final long[] CELL_SCRATCH = new long[125];
    private static final boolean CHECK = net.vesu.Env.get("VESU_RT_LIGHTS_CHECK") != null;

    private static long sectionKey(RtScene.Section s) {
        return ((long) (s.originX >> 4) & 0x3FFFFF) << 42 | ((long) (s.originY >> 4) & 0xFFFFF) << 22
                | ((long) (s.originZ >> 4) & 0x3FFFFF);
    }

    /**
     * Applies emitter changes and uploads the light data when anything changed or the
     * camera moved far from the origin (float precision). Outside rendering, before the
     * RT passes.
     */
    static void update(VkCommandBuffer cmd, List<RtScene.Section> sections, double camX, double camY, double camZ) {
        boolean far = Math.abs(camX - originX) > 1024 || Math.abs(camZ - originZ) > 1024 || Math.abs(camY - originY) > 1024;
        if (!dirty && !far && lights != null) return;
        dirty = false;
        long t0 = System.nanoTime();
        boolean reset = far || lights == null;
        if (reset) {
            originX = (int) Math.floor(camX) & ~(CELL - 1);
            originY = (int) Math.floor(camY) & ~(CELL - 1);
            originZ = (int) Math.floor(camZ) & ~(CELL - 1);
            clearSlots();
        }

        // A section has one RtScene.Section per render layer, all with the same scan: keep one.
        HashMap<Long, RtScene.Section> now = new HashMap<>();
        for (RtScene.Section s : sections) {
            if (s.lights == null || !s.placed) continue;
            now.putIfAbsent(sectionKey(s), s);
        }

        boolean changed = reset;
        for (var it = bySection.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            RtScene.Section s = now.get(e.getKey());
            if (s != null && (s.lights == e.getValue().packed() || java.util.Arrays.equals(s.lights, e.getValue().packed()))) {
                continue;
            }
            removeLights(e.getValue().slots());
            it.remove();
            changed = true;
        }
        // New and changed sections, nearest first: the cap drops the far ones.
        ArrayList<long[]> added = new ArrayList<>();
        for (var e : now.entrySet()) {
            if (bySection.containsKey(e.getKey())) continue;
            RtScene.Section s = e.getValue();
            long dx = s.originX + 8 - (long) camX, dy = s.originY + 8 - (long) camY, dz = s.originZ + 8 - (long) camZ;
            added.add(new long[]{dx * dx + dy * dy + dz * dz, e.getKey()});
        }
        added.sort((x, y) -> Long.compare(x[0], y[0]));
        for (long[] a : added) {
            RtScene.Section s = now.get(a[1]);
            int[] slots = new int[s.lights.length];
            int k = 0;
            for (int packed : s.lights) {
                int slot = allocSlot();
                if (slot < 0) {
                    if (!capWarned) Initializer.LOGGER.warn("[rt] more than {} block lights, the farthest are left out", MAX_LIGHTS);
                    capWarned = true;
                    break;
                }
                float[] rgb = colorTable.get(packed >>> 16);
                float[] lx = LIGHT_SCRATCH;
                lx[slot * 8] = s.originX + (packed & 15) - originX + 0.5f;
                lx[slot * 8 + 1] = s.originY + (packed >> 4 & 15) - originY + 0.5f;
                lx[slot * 8 + 2] = s.originZ + (packed >> 8 & 15) - originZ + 0.5f;
                lx[slot * 8 + 3] = packed >> 12 & 15;
                lx[slot * 8 + 4] = rgb[0];
                lx[slot * 8 + 5] = rgb[1];
                lx[slot * 8 + 6] = rgb[2];
                lx[slot * 8 + 7] = 0.0f;
                int c = cellsOf(slot);
                for (int j = 0; j < c; j++) cellMap.computeIfAbsent(CELL_SCRATCH[j], q -> new Cell()).add(slot);
                slots[k++] = slot;
            }
            bySection.put(a[1], new SectionLights(s.lights, k == slots.length ? slots : java.util.Arrays.copyOf(slots, k)));
            changed = true;
        }
        // Chunk rebuilds without emitter changes (most of them) keep the uploaded data.
        if (!changed) return;
        lightCount = highWater;
        generation++;
        if (CHECK) check();
        long t1 = System.nanoTime();
        uploadAll(cmd);
        if (RtScene.LOG) {
            Initializer.LOGGER.info("[rt] lights: {} slots, {} cells, {} sections{}: apply {} us, table {} us, upload {} us",
                    highWater, cellMap.size(), bySection.size(), reset ? " (reset)" : "", (t1 - t0) / 1000,
                    (tTable - t1) / 1000, (System.nanoTime() - tTable) / 1000);
        }
    }

    private static void clearSlots() {
        bySection.clear();
        cellMap.clear();
        freeTop = 0;
        highWater = 0;
        capWarned = false;
    }

    private static int allocSlot() {
        if (freeTop > 0) return freeSlots[--freeTop];
        return highWater < MAX_LIGHTS ? highWater++ : -1;
    }

    private static final boolean[] REMOVED = new boolean[MAX_LIGHTS];

    /**
     * Takes a section's lights out of their cells: the slots are marked, then each touched
     * cell is compacted once (a crowded cell holds thousands of slots, a search per slot
     * was quadratic).
     */
    private static void removeLights(int[] slots) {
        if (slots.length == 0) return;
        java.util.HashSet<Long> touched = new java.util.HashSet<>();
        for (int slot : slots) {
            REMOVED[slot] = true;
            int c = cellsOf(slot);
            for (int j = 0; j < c; j++) touched.add(CELL_SCRATCH[j]);
        }
        for (Long k : touched) {
            Cell cell = cellMap.get(k);
            if (cell == null) continue;
            int n = 0;
            for (int i = 0; i < cell.n; i++) {
                int slot = cell.slots[i];
                if (!REMOVED[slot]) cell.slots[n++] = slot;
            }
            if (n != cell.n) {
                cell.n = n;
                cell.trimmed = null;
            }
            if (n == 0) cellMap.remove(k);
        }
        for (int slot : slots) {
            REMOVED[slot] = false;
            LIGHT_SCRATCH[slot * 8 + 3] = 0.0f;
            freeSlots[freeTop++] = slot;
        }
    }

    /** Fills CELL_SCRATCH with the keys of the cells the light's sphere reaches; returns how many. */
    private static int cellsOf(int slot) {
        float[] lx = LIGHT_SCRATCH;
        float x = lx[slot * 8], y = lx[slot * 8 + 1], z = lx[slot * 8 + 2], r = lx[slot * 8 + 3];
        int x0 = floorDiv(x - r), x1 = floorDiv(x + r);
        int y0 = floorDiv(y - r), y1 = floorDiv(y + r);
        int z0 = floorDiv(z - r), z1 = floorDiv(z + r);
        int c = 0;
        for (int cx = x0; cx <= x1; cx++)
            for (int cy = y0; cy <= y1; cy++)
                for (int cz = z0; cz <= z1; cz++) {
                    // Skip cells the light sphere misses.
                    float qx = clampF(x, cx * CELL, cx * CELL + CELL) - x;
                    float qy = clampF(y, cy * CELL, cy * CELL + CELL) - y;
                    float qz = clampF(z, cz * CELL, cz * CELL + CELL) - z;
                    if (qx * qx + qy * qy + qz * qz > r * r) continue;
                    CELL_SCRATCH[c++] = key(cx, cy, cz);
                }
        return c;
    }

    /**
     * A crowded cell (a lava sea reaches each with hundreds of blocks) keeps the lights
     * nearest to it: those dominate, and the first come were often far away.
     */
    private static int[] trimmed(long k, Cell cell) {
        if (cell.trimmed != null) return cell.trimmed;
        float[] lx = LIGHT_SCRATCH;
        float mx = ((int) (k >> 42) + 0.5f) * CELL, my = ((int) (k << 22 >> 43) + 0.5f) * CELL,
                mz = ((int) (k << 43 >> 43) + 0.5f) * CELL;
        long[] byDistance = new long[cell.n];
        for (int j = 0; j < cell.n; j++) {
            int li = cell.slots[j];
            float dx = lx[li * 8] - mx, dy = lx[li * 8 + 1] - my, dz = lx[li * 8 + 2] - mz;
            // Brighter lights count as nearer by their level (a lower falloff at the cell).
            float score = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - lx[li * 8 + 3];
            byDistance[j] = (long) Float.floatToIntBits(score + 64.0f) << 32 | li;
        }
        selectSmallest(byDistance, MAX_PER_CELL);
        int[] kept = new int[MAX_PER_CELL];
        for (int j = 0; j < MAX_PER_CELL; j++) kept[j] = (int) byDistance[j];
        cell.trimmed = kept;
        return kept;
    }

    /** Moves the k smallest values to the front of {@code a}, in no order (quickselect). */
    private static void selectSmallest(long[] a, int k) {
        int lo = 0, hi = a.length - 1;
        while (lo < hi) {
            long pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    long t = a[i];
                    a[i++] = a[j];
                    a[j--] = t;
                }
            }
            if (k - 1 <= j) hi = j;
            else if (k - 1 >= i) lo = i;
            else return;
        }
    }

    /** Serializes the slots and the cell hash table and uploads them. */
    private static void uploadAll(VkCommandBuffer cmd) {
        int tableSize = 64;
        while (tableSize < cellMap.size() * 2) tableSize <<= 1;
        gridMask = tableSize - 1;
        int[] table = new int[tableSize * 4];
        for (int i = 0; i < tableSize; i++) table[i * 4] = EMPTY;
        int indexCount = 0;
        for (Cell cell : cellMap.values()) indexCount += Math.min(cell.n, MAX_PER_CELL);
        int[] indices = new int[Math.max(1, indexCount)];
        int at = 0;
        for (var e : cellMap.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 42), cy = (int) (k << 22 >> 43), cz = (int) (k << 43 >> 43);
            Cell cell = e.getValue();
            int count = Math.min(cell.n, MAX_PER_CELL);
            int[] list = cell.n > MAX_PER_CELL ? trimmed(k, cell) : cell.slots;
            int slot = hash(cx, cy, cz) & gridMask;
            while (table[slot * 4] != EMPTY) slot = (slot + 1) & gridMask;
            table[slot * 4] = cx;
            table[slot * 4 + 1] = cy;
            table[slot * 4 + 2] = cz;
            table[slot * 4 + 3] = at | count << 24;
            System.arraycopy(list, 0, indices, at, count);
            at += count;
        }

        tTable = System.nanoTime();
        release();
        lights = upload(cmd, floatsToBytes(LIGHT_SCRATCH, Math.max(1, highWater) * 8));
        grid = upload(cmd, intsToBytes(table, table.length));
        index = upload(cmd, intsToBytes(indices, indices.length));

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkMemoryBarrier2.Buffer b = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack);
            b.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR).dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            VkCompat.pipelineBarrier2(cmd, org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
        }
    }

    /** VESU_RT_LIGHTS_CHECK: the incremental cells must equal cells built from scratch. */
    private static void check() {
        HashMap<Long, java.util.Set<Integer>> fresh = new HashMap<>();
        java.util.Set<Integer> free = new java.util.HashSet<>();
        for (int i = 0; i < freeTop; i++) free.add(freeSlots[i]);
        int active = 0;
        for (int slot = 0; slot < highWater; slot++) {
            if (free.contains(slot)) continue;
            active++;
            int c = cellsOf(slot);
            for (int j = 0; j < c; j++) fresh.computeIfAbsent(CELL_SCRATCH[j], q -> new java.util.HashSet<>()).add(slot);
        }
        int listed = 0;
        for (SectionLights sl : bySection.values()) listed += sl.slots().length;
        boolean ok = fresh.size() == cellMap.size() && listed == active;
        for (var e : fresh.entrySet()) {
            Cell cell = cellMap.get(e.getKey());
            if (cell == null || cell.n != e.getValue().size()) { ok = false; break; }
            for (int i = 0; i < cell.n; i++) if (!e.getValue().contains(cell.slots[i])) { ok = false; break; }
        }
        Initializer.LOGGER.info("[rt] lights check {}: {} lights, {} cells", ok ? "ok" : "FAILED", active, cellMap.size());
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
        clearSlots();
        dirty = true;
    }
}
