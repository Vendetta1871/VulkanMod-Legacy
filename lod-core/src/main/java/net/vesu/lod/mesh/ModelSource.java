package net.vesu.lod.mesh;

/** The bakery's tables as the mesher sees them (AUDIT 5.14). */
public interface ModelSource {
    /** No model: the state draws nothing and occludes nothing (unknown ids, invisible blocks). */
    int NONE = -2;
    /** Not baked yet: the build is retried after a bake. */
    int UNBAKED = -1;

    /** Model id of a LOD state id, {@link #NONE} or {@link #UNBAKED}. State 0 (air) is never asked. */
    int model(int stateId);

    long meta(int model);
}
