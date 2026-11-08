package net.vesu.lod.mc;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.vesu.lod.LodRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.12.2 ids to LOD ids (AUDIT 5.2). The chunk storage id of a state is
 * {@code blockId << 4 | meta}; its persistent key is {@code modid:name[prop=value,...]} with
 * the properties in sorted order, so LOD data survives id changes between sessions.
 */
public final class StateMapper {
    private static Map<String, IBlockState> keyToState;

    private final LodRegistry registry;
    /** Storage id -> LOD id + 1 (0 = not looked up yet). Racy writes are idempotent. */
    private final int[] states = new int[1 << 16];
    private final int[] biomes = new int[256];
    /** Ids past the arrays (id extenders: more than 4096 blocks or 256 biomes). */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> wideStates = new java.util.concurrent.ConcurrentHashMap<>(),
            wideBiomes = new java.util.concurrent.ConcurrentHashMap<>();

    public StateMapper(LodRegistry registry) {
        this.registry = registry;
    }

    public static String key(IBlockState state) {
        ResourceLocation name = Block.REGISTRY.getNameForObject(state.getBlock());
        StringBuilder sb = new StringBuilder(String.valueOf(name));
        if (!state.getProperties().isEmpty()) {
            List<String> props = new ArrayList<>();
            for (Map.Entry<IProperty<?>, Comparable<?>> e : state.getProperties().entrySet()) {
                props.add(e.getKey().getName() + "=" + valueName(e.getKey(), e.getValue()));
            }
            props.sort(null);
            sb.append('[').append(String.join(",", props)).append(']');
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(IProperty p, Comparable v) {
        return p.getName(v);
    }

    public static boolean isAir(IBlockState state) {
        return state.getMaterial() == Material.AIR;
    }

    private static synchronized Map<String, IBlockState> states() {
        if (keyToState == null) {
            Map<String, IBlockState> m = new HashMap<>();
            for (Block b : Block.REGISTRY) {
                for (IBlockState s : b.getBlockState().getValidStates()) m.putIfAbsent(key(s), s);
            }
            keyToState = m;
        }
        return keyToState;
    }

    /** The state of a persistent key, or null when the block no longer exists. */
    public static IBlockState stateOf(String key) {
        return key == null ? null : states().get(key);
    }

    /** Light opacity of a key for the registry's mip rule; unknown keys give 0. */
    public static int opacityOf(String key) {
        IBlockState s = stateOf(key);
        return s == null ? 0 : Math.min(15, s.getLightOpacity());
    }

    /** The runtime state of a LOD id, or null. */
    public IBlockState stateOf(int lodId) {
        return stateOf(registry.stateKey(lodId));
    }

    @SuppressWarnings("deprecation")
    public int lodState(int storageId) {
        if (storageId < 0) return 0;
        boolean wide = storageId >= states.length;
        int c = wide ? wideStates.getOrDefault(storageId, 0) : states[storageId];
        if (c != 0) return c - 1;
        IBlockState s = Block.BLOCK_STATE_IDS.getByValue(storageId);
        if (s == null) {
            Block b = Block.getBlockById(storageId >> 4);
            if (b == null) return 0;
            try {
                s = b.getStateFromMeta(storageId & 15);
            } catch (RuntimeException e) {
                s = b.getDefaultState();
            }
        }
        int id = s == null || isAir(s) ? 0 : registry.stateId(key(s));
        if (wide) wideStates.put(storageId, id + 1);
        else states[storageId] = id + 1;
        return id;
    }

    public int lodBiome(int biomeId) {
        boolean wide = biomeId < 0 || biomeId >= biomes.length;
        int c = wide ? wideBiomes.getOrDefault(biomeId, 0) : biomes[biomeId];
        if (c != 0) return c - 1;
        Biome b = biomeId < 0 ? null : Biome.getBiome(biomeId);
        ResourceLocation name = b == null ? null : Biome.REGISTRY.getNameForObject(b);
        int id = registry.biomeId(name == null ? "minecraft:plains" : name.toString());
        if (wide) wideBiomes.put(biomeId, id + 1);
        else biomes[biomeId] = id + 1;
        return id;
    }

    /** The runtime biome of a LOD biome id (plains when unknown). */
    public Biome biomeOf(int lodBiome) {
        String key = registry.biomeKey(lodBiome);
        Biome b = key == null ? null : Biome.REGISTRY.getObject(new ResourceLocation(key));
        return b != null ? b : net.minecraft.init.Biomes.PLAINS;
    }
}
