package net.vesu.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.vesu.mixin.render.ContainerInfoAccessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shorter stand-ins for {@code RenderGlobal.renderInfos}: a far view has thousands of visible
 * sections and vanilla walks all of them for entities, for tile entities and for each block
 * layer, every frame, to find the few that hold something. The lists are built once per flood:
 * vanilla floods again whenever visibility or a section's contents changed, with new elements.
 */
public final class VisibleSections {

    private static List<?> source;
    private static Object lastInfo;
    private static int lastSize = -1, originX, originZ, side;
    private static Object[] table = new Object[0];
    private static final List<Object> tiles = new ArrayList<>();
    private static final List<List<Object>> layers = new ArrayList<>();
    private static final List<Object> entities = new ArrayList<>();
    private static final int[] touched = new int[4096];
    private static int touchedCount;

    static {
        for (int i = 0; i < BlockRenderLayer.values().length; i++) layers.add(new ArrayList<>());
    }

    private VisibleSections() {
    }

    /** Sections that can draw {@code layer}, in the order of {@code renderInfos}. */
    public static List<?> forLayer(List<?> renderInfos, BlockRenderLayer layer) {
        refresh(renderInfos);
        return layers.get(layer.ordinal());
    }

    /** Sections with tile entities. */
    public static List<?> withTileEntities(List<?> renderInfos) {
        refresh(renderInfos);
        return tiles;
    }

    /** The visible sections that hold an entity right now, found from the entities. */
    public static List<?> withEntities(List<?> renderInfos) {
        refresh(renderInfos);
        entities.clear();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return entities;
        touchedCount = 0;
        List<Entity> loaded = mc.world.loadedEntityList;
        for (int i = 0, n = loaded.size(); i < n; i++) {
            Entity e = loaded.get(i);
            if (!e.addedToChunk) continue;
            int index = index(e.chunkCoordX, MathHelper.clamp(e.chunkCoordY, 0, 15), e.chunkCoordZ);
            if (index < 0 || table[index] == null || seenThisFrame(index)) continue;
            entities.add(table[index]);
            if (touchedCount < touched.length) touched[touchedCount++] = index;
        }
        return entities;
    }

    private static boolean seenThisFrame(int index) {
        for (int i = 0; i < touchedCount; i++) {
            if (touched[i] == index) return true;
        }
        return false;
    }

    private static void refresh(List<?> renderInfos) {
        int size = renderInfos.size();
        Object last = size > 0 ? renderInfos.get(size - 1) : null;
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc.getRenderViewEntity();
        if (viewer == null) return;
        int wantedSide = 2 * mc.gameSettings.renderDistanceChunks + 3;
        int wantedX = MathHelper.floor(viewer.posX / 16.0) - wantedSide / 2, wantedZ = MathHelper.floor(viewer.posZ / 16.0) - wantedSide / 2;
        if (renderInfos == source && size == lastSize && last == lastInfo && wantedSide == side && wantedX == originX && wantedZ == originZ) {
            return;
        }
        source = renderInfos;
        lastSize = size;
        lastInfo = last;
        side = wantedSide;
        originX = wantedX;
        originZ = wantedZ;
        touchedCount = 0;
        int length = side * side * 16;
        if (table.length != length) table = new Object[length];
        else Arrays.fill(table, null);
        tiles.clear();
        for (List<Object> list : layers) list.clear();
        BlockRenderLayer[] all = BlockRenderLayer.values();
        for (Object info : renderInfos) {
            RenderChunk chunk = ((ContainerInfoAccessor) info).vesu$getRenderChunk();
            BlockPos p = chunk.getPosition();
            int index = index(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
            if (index >= 0) table[index] = info;
            net.minecraft.client.renderer.chunk.CompiledChunk compiled = chunk.getCompiledChunk();
            if (!compiled.getTileEntities().isEmpty()) tiles.add(info);
            if (compiled.isEmpty()) continue;
            for (BlockRenderLayer layer : all) {
                if (!compiled.isLayerEmpty(layer)) layers.get(layer.ordinal()).add(info);
            }
        }
    }

    private static int index(int x, int y, int z) {
        int dx = x - originX, dz = z - originZ;
        if (dx < 0 || dz < 0 || dx >= side || dz >= side || y < 0 || y > 15) return -1;
        return (dx * side + dz) * 16 + y;
    }
}
