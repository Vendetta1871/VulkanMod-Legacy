package net.vesu.lod.mc;

import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.vesu.Initializer;
import net.vesu.lod.bake.BakedModel;
import net.vesu.lod.bake.FaceBaker;
import net.vesu.lod.mesh.ModelSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The model bakery (AUDIT 5.14): its own thread, {@code Vesu LOD Bakery}. A LOD state is
 * baked from {@code getQuads} of its block model in every layer it renders in (liquids get
 * a synthesized block with the top at 14/16), rasterized into six face images, and given a
 * model id; identical models share one. The GPU side drains {@link #uploads}.
 */
public final class Bakery {
    /** One model ready for the GPU: its record, face tiles and, for biome tints, its colors per LOD biome. */
    public record Upload(int model, int[] record, int[][] tiles) {}

    /** Biome tint slots of 512 LOD biomes each in a 65 536-entry table. */
    public static final int TINT_STRIDE = 512, TINT_SLOTS = 128;

    final ModelTables tables = new ModelTables();
    private final StateMapper mapper;
    private final LinkedBlockingQueue<Integer> requests = new LinkedBlockingQueue<>();
    private final java.util.Set<Integer> requested = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public final ConcurrentLinkedQueue<Upload> uploads = new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> dedup = new HashMap<>();
    /** States of each biome-tinted model by tint slot, to fill colors for new biomes. */
    private final List<IBlockState> tintStates = new ArrayList<>();
    private final List<Integer> tintIndices = new ArrayList<>();
    private int modelCount;
    private volatile int[] atlas;
    private volatile int atlasW, atlasH;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile Runnable onBatch = () -> { };
    private final BiomeAccess access = new BiomeAccess();
    /** Biome tint colors, {@code slot * TINT_STRIDE + lodBiome}; the GPU side copies it when {@link #tintVersion} moves. */
    public final int[] tintTable = new int[TINT_STRIDE * TINT_SLOTS];
    public volatile int tintVersion;
    private int tintBiomes;

    Bakery(StateMapper mapper) {
        this.mapper = mapper;
        thread = new Thread(this::loop, "Vesu LOD Bakery");
        thread.setDaemon(true);
        thread.setPriority(4);
        thread.start();
    }

    void setOnBatch(Runnable r) {
        onBatch = r;
    }

    /** The block atlas as ARGB, read back on the client thread. */
    void setAtlas(int[] argb, int w, int h) {
        atlasW = w;
        atlasH = h;
        atlas = argb;
    }

    public int modelCount() {
        return modelCount;
    }

    public int pending() {
        return requests.size();
    }

    void request(int lodState) {
        if (lodState <= 0 || tables.model(lodState) != ModelSource.UNBAKED) return;
        if (requested.add(lodState)) requests.add(lodState);
    }

    void stop() {
        running = false;
        thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                Integer id = requests.poll(250, TimeUnit.MILLISECONDS);
                if (id == null) continue;
                if (atlas == null) {
                    // No atlas yet: try again shortly.
                    requests.add(id);
                    Thread.sleep(50);
                    continue;
                }
                int n = 0;
                do {
                    bakeState(id);
                    requested.remove(id);
                    n++;
                } while (n < 256 && (id = requests.poll()) != null);
                refreshTintBiomes();
                onBatch.run();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Initializer.LOGGER.error("[lod] bakery failed", t);
            }
        }
    }

    private void bakeState(int lodState) {
        if (tables.model(lodState) != ModelSource.UNBAKED) return;
        IBlockState state = mapper.stateOf(lodState);
        int model = ModelSource.NONE;
        try {
            if (state != null) model = bake(state);
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[lod] cannot bake {}: {}", mapper.stateOf(lodState), t.toString());
        }
        tables.set(lodState, model);
    }

    private int argb(float u, float v) {
        int[] a = atlas;
        int w = atlasW, h = atlasH;
        int x = Math.max(0, Math.min(w - 1, Math.round(u * w - 0.5f)));
        int y = Math.max(0, Math.min(h - 1, Math.round(v * h - 0.5f)));
        return a[y * w + x];
    }

    @SuppressWarnings("deprecation")
    private int bake(IBlockState state) {
        EnumBlockRenderType type = state.getRenderType();
        if (type == EnumBlockRenderType.INVISIBLE || type == EnumBlockRenderType.ENTITYBLOCK_ANIMATED) return ModelSource.NONE;
        List<FaceBaker.Quad> quads = new ArrayList<>();
        boolean translucentLayer = false, shaded = false, fluid = false;
        int tintIndex = -1;
        if (type == EnumBlockRenderType.LIQUID || state.getBlock() instanceof BlockLiquid) {
            fluid = true;
            boolean water = state.getMaterial() == Material.WATER;
            translucentLayer = water;
            shaded = true;
            if (water) tintIndex = 0;
            TextureAtlasSprite still = Minecraft.getMinecraft().getTextureMapBlocks()
                    .getAtlasSprite(water ? "minecraft:blocks/water_still" : "minecraft:blocks/lava_still");
            synthesizeFluid(quads, still, water, 14f / 16f);
        } else {
            IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelForState(state);
            for (BlockRenderLayer layer : BlockRenderLayer.values()) {
                if (!state.getBlock().canRenderInLayer(state, layer)) continue;
                net.minecraftforge.client.ForgeHooksClient.setRenderLayer(layer);
                try {
                    for (EnumFacing side : EnumFacing.values()) addQuads(quads, model.getQuads(state, side, 42L), layer);
                    addQuads(quads, model.getQuads(state, null, 42L), layer);
                } finally {
                    net.minecraftforge.client.ForgeHooksClient.setRenderLayer(null);
                }
                if (layer == BlockRenderLayer.TRANSLUCENT) translucentLayer = true;
            }
            for (FaceBaker.Quad q : quads) {
                if (q.tinted && tintIndex < 0) tintIndex = quadTint.getOrDefault(q, 0);
            }
            shaded = shadedFlag;
            quadTint.clear();
            shadedFlag = false;
        }
        if (quads.isEmpty()) return ModelSource.NONE;
        long[][] images = FaceBaker.bake(quads, this::argb);
        boolean leaves = state.getBlock() instanceof BlockLeaves || state.getMaterial() == Material.LEAVES;
        int tintKind = 0, tint = -1;
        if (tintIndex >= 0) {
            int a = color(state, net.minecraft.init.Biomes.PLAINS, tintIndex);
            int b = color(state, net.minecraft.init.Biomes.SWAMPLAND, tintIndex);
            int c = color(state, net.minecraft.init.Biomes.DESERT, tintIndex);
            if (a == b && b == c) {
                tintKind = 1;
                tint = a | 0xFF000000;
            } else {
                tintKind = 2;
            }
        }
        BakedModel baked = new BakedModel(images, translucentLayer && !leaves, leaves, fluid, state.isOpaqueCube(),
                state.getLightValue(), tintKind, tint, shaded, 0);
        Integer known = dedup.get(baked.dedupKey);
        if (known != null) return known;
        if (modelCount >= ModelTables.MAX_MODELS) return ModelSource.NONE;
        int id = modelCount++;
        if (tintKind == 2) {
            int slot = tintStates.size();
            if (slot >= TINT_SLOTS) {
                baked.record[6] &= ~2;
                baked.setTint(color(state, net.minecraft.init.Biomes.PLAINS, tintIndex) | 0xFF000000);
            } else {
                tintStates.add(state);
                tintIndices.add(tintIndex);
                baked.setTint(slot * TINT_STRIDE);
                fillTint(slot, 0);
            }
        }
        dedup.put(baked.dedupKey, id);
        tables.setMeta(id, baked.meta);
        uploads.add(new Upload(id, baked.record, baked.tiles));
        return id;
    }

    private final Map<FaceBaker.Quad, Integer> quadTint = new HashMap<>();
    private boolean shadedFlag;

    private void addQuads(List<FaceBaker.Quad> out, List<BakedQuad> in, BlockRenderLayer layer) {
        for (BakedQuad bq : in) {
            VertexFormat fmt = bq.getFormat();
            int stride = fmt.getIntegerSize();
            int pos = -1, uv = -1, col = -1;
            for (int i = 0; i < fmt.getElementCount(); i++) {
                VertexFormatElement e = fmt.getElement(i);
                int off = fmt.getOffset(i) / 4;
                if (e.getUsage() == VertexFormatElement.EnumUsage.POSITION) pos = off;
                else if (e.getUsage() == VertexFormatElement.EnumUsage.UV && e.getIndex() == 0) uv = off;
                else if (e.getUsage() == VertexFormatElement.EnumUsage.COLOR) col = off;
            }
            if (pos < 0 || uv < 0) continue;
            int[] d = bq.getVertexData();
            if (d.length < stride * 4) continue;
            FaceBaker.Quad q = new FaceBaker.Quad();
            for (int v = 0; v < 4; v++) {
                int b = v * stride;
                q.x[v] = Float.intBitsToFloat(d[b + pos]);
                q.y[v] = Float.intBitsToFloat(d[b + pos + 1]);
                q.z[v] = Float.intBitsToFloat(d[b + pos + 2]);
                q.u[v] = Float.intBitsToFloat(d[b + uv]);
                q.v[v] = Float.intBitsToFloat(d[b + uv + 1]);
            }
            if (col >= 0) {
                int abgr = d[col];
                q.color = FaceBaker.abgrToArgb(abgr);
                if ((q.color >>> 24) == 0) q.color |= 0xFF000000;
            }
            q.tinted = bq.hasTintIndex();
            if (q.tinted) quadTint.put(q, bq.getTintIndex());
            q.translucent = layer == BlockRenderLayer.TRANSLUCENT;
            q.cutout = layer == BlockRenderLayer.CUTOUT || layer == BlockRenderLayer.CUTOUT_MIPPED;
            if (bq.shouldApplyDiffuseLighting()) shadedFlag = true;
            out.add(q);
        }
    }

    private static void synthesizeFluid(List<FaceBaker.Quad> out, TextureAtlasSprite s, boolean water, float top) {
        float[][][] faces = {
                {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
                {{0, top, 0}, {0, top, 1}, {1, top, 1}, {1, top, 0}},
                {{0, 0, 0}, {0, top, 0}, {1, top, 0}, {1, 0, 0}},
                {{0, 0, 1}, {1, 0, 1}, {1, top, 1}, {0, top, 1}},
                {{0, 0, 0}, {0, 0, 1}, {0, top, 1}, {0, top, 0}},
                {{1, 0, 0}, {1, top, 0}, {1, top, 1}, {1, 0, 1}},
        };
        for (int f = 0; f < 6; f++) {
            FaceBaker.Quad q = new FaceBaker.Quad();
            for (int v = 0; v < 4; v++) {
                float[] p = faces[f][v];
                q.x[v] = p[0];
                q.y[v] = p[1];
                q.z[v] = p[2];
                // u along the face's first axis, v along the second, like the face images.
                float a = f < 2 ? p[0] : f < 4 ? p[0] : p[2];
                float b = f < 2 ? p[2] : p[1];
                q.u[v] = s.getInterpolatedU(a * 16);
                q.v[v] = s.getInterpolatedV(16 - b * 16);
            }
            q.tinted = water;
            q.translucent = water;
            out.add(q);
        }
    }

    private int color(IBlockState state, Biome biome, int tintIndex) {
        synchronized (access) {
            access.biome = biome;
            access.state = state;
            try {
                return Minecraft.getMinecraft().getBlockColors().colorMultiplier(state, access, BlockPos.ORIGIN, tintIndex) | 0xFF000000;
            } catch (RuntimeException e) {
                return -1;
            }
        }
    }

    /** Fills a tint slot's colors from LOD biome {@code from} on. */
    private void fillTint(int slot, int from) {
        int n = Math.min(TINT_STRIDE, mapper == null ? 0 : registryBiomes());
        IBlockState st = tintStates.get(slot);
        int ti = tintIndices.get(slot);
        for (int b = from; b < n; b++) tintTable[slot * TINT_STRIDE + b] = color(st, mapper.biomeOf(b), ti);
        tintVersion++;
    }

    private int registryBiomes() {
        return LodSystem.registryBiomes();
    }

    private void refreshTintBiomes() {
        int n = registryBiomes();
        if (n == tintBiomes) return;
        for (int s = 0; s < tintStates.size(); s++) fillTint(s, tintBiomes);
        tintBiomes = n;
    }

    /** Called when biomes appear: the tint table catches up on the bake thread. */
    void nudge() {
        if (registryBiomes() != tintBiomes) {
            requests.add(0);
        }
    }
}
