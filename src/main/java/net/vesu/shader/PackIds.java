package net.vesu.shader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.vesu.pack.ir.IrPack;

import java.util.HashMap;
import java.util.Map;

/** {@code block.properties}, {@code item.properties} and {@code entity.properties} of the active pack (frontends.md, "ID maps"). */
public final class PackIds {

    /** Vertex attribute per tag (legacy id | meta << 12): id in the low 16 bits, then (render layer << 8 | meta) above. */
    private static int[] blockTable;
    private static final Map<ResourceLocation, Integer> entities = new HashMap<>();
    private static final Map<Item, Integer> items = new HashMap<>();
    private static IrPack loaded;

    private PackIds() {
    }

    static void load(IrPack pack) {
        loaded = pack;
        blockTable = null;
        entities.clear();
        items.clear();
    }

    private static void resolve() {
        if (blockTable != null || loaded == null) return;
        int[] table = new int[65536];
        boolean noVanillaIds = loaded.renderer.has("unmappedBlockId");
        for (Block block : Block.REGISTRY) {
            int id = Block.getIdFromBlock(block) & 0xFFF;
            for (int meta = 0; meta < 16; meta++) {
                IBlockState state;
                try {
                    state = block.getStateFromMeta(meta);
                } catch (RuntimeException e) {
                    continue;
                }
                table[id | meta << 12] = (noVanillaIds ? 0 : id) | (block.getRenderLayer().ordinal() << 8 | meta) << 16;
            }
        }
        JsonObject map = loaded.raw.has("blockIds") ? loaded.raw.getAsJsonObject("blockIds") : null;
        if (map != null) {
            // An Iris pack names blocks as a flattened game does. Translated tokens go last: they
            // pick a variant out of a 1.12 block that a plain token of the pack tags as a whole.
            boolean iris = noVanillaIds;
            java.util.List<String> late = new java.util.ArrayList<>();
            java.util.List<Integer> lateIds = new java.util.ArrayList<>();
            for (Map.Entry<String, JsonElement> e : map.entrySet()) {
                int mapped;
                try {
                    mapped = Integer.parseInt(e.getKey());
                } catch (NumberFormatException ex) {
                    continue;
                }
                for (String token : e.getValue().getAsString().trim().split("\\s+")) {
                    String[] legacy = iris ? LegacyBlockNames.translate(token) : null;
                    if (legacy == null) {
                        applyBlock(table, token, mapped, iris);
                        continue;
                    }
                    for (String t : legacy) {
                        late.add(t);
                        lateIds.add(mapped);
                    }
                }
            }
            for (int i = 0; i < late.size(); i++) applyBlock(table, late.get(i), lateIds.get(i), true);
        }
        blockTable = table;
        JsonObject entityMap = loaded.raw.has("entityIds") ? loaded.raw.getAsJsonObject("entityIds") : null;
        if (entityMap != null) {
            for (Map.Entry<String, JsonElement> e : entityMap.entrySet()) {
                for (String token : e.getValue().getAsString().trim().split("\\s+")) {
                    try {
                        entities.put(new ResourceLocation(token), Integer.parseInt(e.getKey()));
                    } catch (RuntimeException ignored) {
                        // A malformed line of a map does not stop the pack.
                    }
                }
            }
        }
        JsonObject itemMap = loaded.raw.has("itemIds") ? loaded.raw.getAsJsonObject("itemIds") : null;
        if (itemMap != null) {
            for (Map.Entry<String, JsonElement> e : itemMap.entrySet()) {
                for (String token : e.getValue().getAsString().trim().split("\\s+")) {
                    Item item = token.matches("\\d+") ? Item.getItemById(Integer.parseInt(token)) : Item.getByNameOrId(token);
                    if (item != null) {
                        try {
                            items.put(item, Integer.parseInt(e.getKey()));
                        } catch (NumberFormatException ignored) {
                            // As above.
                        }
                    }
                }
            }
        }
    }

    /** {@code [domain:]name[:meta,meta...][:property=value,value...]} or a numeric id. */
    private static void applyBlock(int[] table, String token, int mapped, boolean flattened) {
        String[] parts = token.split(":");
        Block block;
        int next;
        if (parts[0].matches("\\d+")) {
            block = Block.getBlockById(Integer.parseInt(parts[0]));
            next = 1;
        } else if (parts.length >= 2 && !parts[1].matches("\\d.*") && !parts[1].contains("=")) {
            block = Block.getBlockFromName(parts[0] + ":" + parts[1]);
            next = 2;
        } else {
            block = Block.getBlockFromName(parts[0]);
            next = 1;
        }
        if (block == null) return;
        int id = Block.getIdFromBlock(block) & 0xFFF;
        boolean[] metas = new boolean[16];
        java.util.Arrays.fill(metas, true);
        boolean numeric = false;
        boolean[] listed = new boolean[16];
        for (int i = next; i < parts.length; i++) {
            if (parts[i].contains("=")) {
                if (!matchProperty(block, parts[i], metas, flattened)) return;
                continue;
            }
            for (String m : parts[i].split(",")) {
                if (m.matches("\\d+")) {
                    numeric = true;
                    int v = Integer.parseInt(m);
                    if (v < 16) listed[v] = true;
                }
            }
        }
        for (int meta = 0; meta < 16; meta++) {
            if (!metas[meta] || (numeric && !listed[meta])) continue;
            int at = id | meta << 12;
            table[at] = (table[at] & 0xFFFF0000) | (mapped & 0xFFFF);
        }
    }

    /**
     * {@code name=value,value}: clears the metas of other values. A property the block does not
     * have drops the whole entry, as in OptiFine, so {@code leaves:waterlogged=false} of a pack
     * written for newer versions tags nothing here. With {@code flattened} (Iris packs) a missing
     * {@code waterlogged} reads as false, as nothing holds water on 1.12.
     */
    private static boolean matchProperty(Block block, String selector, boolean[] metas, boolean flattened) {
        String[] kv = selector.split("=", 2);
        net.minecraft.block.properties.IProperty<?> property = null;
        for (net.minecraft.block.properties.IProperty<?> p : block.getBlockState().getProperties()) {
            if (p.getName().equals(kv[0])) property = p;
        }
        if (property == null) {
            return flattened && kv[0].equals("waterlogged") && kv.length == 2
                    && java.util.Arrays.asList(kv[1].split(",")).contains("false");
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(java.util.Arrays.asList(kv[1].split(",")));
        for (int meta = 0; meta < 16; meta++) {
            if (!metas[meta]) continue;
            try {
                metas[meta] = wanted.contains(valueName(block.getStateFromMeta(meta), property));
            } catch (RuntimeException e) {
                metas[meta] = false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(net.minecraft.block.state.IBlockState state,
                                                              net.minecraft.block.properties.IProperty<T> property) {
        return property.getName(state.getValue(property));
    }

    /** The attribute of a tagged vertex: id, then (layer << 8 | meta) in the high half. */
    public static int block(int tag) {
        if (blockTable == null) {
            resolve();
            if (blockTable == null) return tag & 0xFFF | (tag >>> 12 & 0xF) << 16;
        }
        return blockTable[tag & 0xFFFF];
    }

    public static int entityId(Entity entity) {
        if (entity == null || entities.isEmpty()) return -1;
        ResourceLocation key = EntityList.getKey(entity);
        Integer id = key == null ? null : entities.get(key);
        return id == null ? -1 : id;
    }

    /** {@code block.properties} of the block a block entity stands on, -1 outside one. */
    public static int blockEntityId(net.minecraft.tileentity.TileEntity tile) {
        if (tile == null || tile.getBlockType() == null) return -1;
        int tag = Block.getIdFromBlock(tile.getBlockType()) & 0xFFF | (tile.getBlockMetadata() & 0xF) << 12;
        return block(tag) & 0xFFFF;
    }

    /** {@code currentRenderedItemId}: only {@code item.properties} gives an item an id. */
    public static int renderedItemId(Item item) {
        if (loaded != null && blockTable == null) resolve();
        Integer id = items.get(item);
        return id != null ? id : 0;
    }

    public static int itemId(Item item) {
        if (loaded != null && blockTable == null) resolve();
        Integer id = items.get(item);
        return id != null ? id : Item.getIdFromItem(item);
    }
}
