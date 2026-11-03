package net.vesu.lod.mc;

import net.vesu.lod.mesh.ModelSource;

import java.util.Arrays;

/**
 * The bakery's lookup tables (AUDIT 5.14): state -> model id (2^20 entries) and model ->
 * metadata (2^16). Written by the bake thread only after a model's GPU data is queued, read
 * by the meshers.
 */
public final class ModelTables implements ModelSource {
    public static final int MAX_MODELS = 1 << 16;
    private final int[] stateModel = new int[1 << 20];
    private final long[] meta = new long[MAX_MODELS];

    ModelTables() {
        clear();
    }

    void clear() {
        Arrays.fill(stateModel, UNBAKED);
        stateModel[0] = NONE;
    }

    void set(int state, int model) {
        stateModel[state] = model;
    }

    void setMeta(int model, long m) {
        meta[model] = m;
    }

    @Override
    public int model(int stateId) {
        return stateId < stateModel.length ? stateModel[stateId] : NONE;
    }

    @Override
    public long meta(int model) {
        return meta[model];
    }
}
