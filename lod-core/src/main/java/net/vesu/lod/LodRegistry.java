package net.vesu.lod;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Persistent id maps of one LOD world (AUDIT 5.3): state key <-> int (type 1) and biome key
 * <-> int (type 2), stored in the mappings table under {@code id | type << 30}. State 0 is
 * air. Each state caches its light opacity for the mip rule. Ids only ever grow, so a blob
 * stays valid across sessions and mod changes (an unknown key resolves to no model).
 */
public final class LodRegistry implements Mip.Opacity {
    public static final int TYPE_STATE = 1, TYPE_BIOME = 2;

    /** Called for every new state id, e.g. so the bakery can bake it ahead of meshing. */
    public interface NewStateListener {
        void onNewState(int id, String key);
    }

    private final LodStorage storage;
    private final ToIntFunction<String> opacityOf;
    private final ConcurrentHashMap<String, Integer> stateIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> biomeIds = new ConcurrentHashMap<>();
    private volatile String[] stateKeys = new String[256];
    private volatile String[] biomeKeys = new String[64];
    private volatile byte[] opacity = new byte[256];
    private int stateCount = 1, biomeCount;
    private volatile NewStateListener listener;

    /**
     * @param opacityOf light opacity 0..15 of a state key; keys that no longer resolve give 0.
     */
    public LodRegistry(LodStorage storage, ToIntFunction<String> opacityOf) {
        this.storage = storage;
        this.opacityOf = opacityOf;
        stateKeys[0] = "minecraft:air";
        stateIds.put("minecraft:air", 0);
        storage.forEachMapping((k, v) -> {
            int type = k >>> 30, id = k & ((1 << 30) - 1);
            String key = new String(v, StandardCharsets.UTF_8);
            if (type == TYPE_STATE && id > 0 && id <= Voxel.MAX_STATE) {
                putState(id, key, opacityOf.applyAsInt(key));
            } else if (type == TYPE_BIOME && id <= Voxel.MAX_BIOME) {
                putBiome(id, key);
            }
        });
    }

    public void setListener(NewStateListener listener) {
        this.listener = listener;
    }

    private synchronized void putState(int id, String key, int op) {
        if (id >= stateKeys.length) {
            int n = Integer.highestOneBit(id) * 2;
            stateKeys = Arrays.copyOf(stateKeys, n);
            opacity = Arrays.copyOf(opacity, n);
        }
        stateKeys[id] = key;
        opacity[id] = (byte) Math.max(0, Math.min(15, op));
        stateIds.put(key, id);
        stateCount = Math.max(stateCount, id + 1);
    }

    private synchronized void putBiome(int id, String key) {
        if (id >= biomeKeys.length) biomeKeys = Arrays.copyOf(biomeKeys, Integer.highestOneBit(id) * 2);
        biomeKeys[id] = key;
        biomeIds.put(key, id);
        biomeCount = Math.max(biomeCount, id + 1);
    }

    /** The id of a state key, created (and persisted) on first sight. Air keys map to 0 by the caller. */
    public int stateId(String key) {
        Integer id = stateIds.get(key);
        if (id != null) return id;
        int created;
        synchronized (this) {
            id = stateIds.get(key);
            if (id != null) return id;
            if (stateCount > Voxel.MAX_STATE) return 0;
            created = stateCount;
            putState(created, key, opacityOf.applyAsInt(key));
        }
        storage.putMapping(created | TYPE_STATE << 30, key.getBytes(StandardCharsets.UTF_8));
        NewStateListener l = listener;
        if (l != null) l.onNewState(created, key);
        return created;
    }

    public int biomeId(String key) {
        Integer id = biomeIds.get(key);
        if (id != null) return id;
        int created;
        synchronized (this) {
            id = biomeIds.get(key);
            if (id != null) return id;
            if (biomeCount > Voxel.MAX_BIOME) return 0;
            created = biomeCount;
            putBiome(created, key);
        }
        storage.putMapping(created | TYPE_BIOME << 30, key.getBytes(StandardCharsets.UTF_8));
        return created;
    }

    /** -1 when unknown. */
    public int lookupState(String key) {
        Integer id = stateIds.get(key);
        return id == null ? -1 : id;
    }

    public String stateKey(int id) {
        String[] k = stateKeys;
        return id >= 0 && id < k.length ? k[id] : null;
    }

    public String biomeKey(int id) {
        String[] k = biomeKeys;
        return id >= 0 && id < k.length ? k[id] : null;
    }

    public int stateCount() {
        return stateCount;
    }

    public int biomeCount() {
        return biomeCount;
    }

    @Override
    public int of(int stateId) {
        byte[] o = opacity;
        return stateId < o.length ? o[stateId] : 0;
    }
}
