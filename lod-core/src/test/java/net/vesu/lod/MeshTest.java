package net.vesu.lod;

import net.vesu.lod.bake.BakedModel;
import net.vesu.lod.bake.FaceBaker;
import net.vesu.lod.mesh.MeshResult;
import net.vesu.lod.mesh.Mesher;
import net.vesu.lod.mesh.ModelMeta;
import net.vesu.lod.mesh.ModelSource;
import net.vesu.lod.mesh.Quad;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MeshTest {
    /** A unit cube with its six faces, counter-clockwise from outside. */
    static List<FaceBaker.Quad> cube(float y1) {
        float[][][] f = {
                {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},          // down
                {{0, y1, 0}, {0, y1, 1}, {1, y1, 1}, {1, y1, 0}},      // up
                {{0, 0, 0}, {0, y1, 0}, {1, y1, 0}, {1, 0, 0}},        // north (-z)
                {{0, 0, 1}, {1, 0, 1}, {1, y1, 1}, {0, y1, 1}},        // south
                {{0, 0, 0}, {0, 0, 1}, {0, y1, 1}, {0, y1, 0}},        // west
                {{1, 0, 0}, {1, y1, 0}, {1, y1, 1}, {1, 0, 1}},        // east
        };
        List<FaceBaker.Quad> out = new ArrayList<>();
        for (float[][] face : f) {
            FaceBaker.Quad q = new FaceBaker.Quad();
            for (int i = 0; i < 4; i++) {
                q.x[i] = face[i][0];
                q.y[i] = face[i][1];
                q.z[i] = face[i][2];
                q.u[i] = q.x[i];
                q.v[i] = q.z[i];
            }
            out.add(q);
        }
        return out;
    }

    @Test
    void bakeCube() {
        long[][] img = FaceBaker.bake(cube(1), (u, v) -> 0xFF336699);
        for (int f = 0; f < 6; f++) {
            for (long p : img[f]) assertNotEquals(FaceBaker.CLEAR, p, "face " + f);
            assertEquals(0, img[f][0] >>> 40, "face " + f + " depth at the surface");
        }
        BakedModel m = new BakedModel(img, false, false, false, true, 0, 0, -1, true, 0);
        assertTrue(ModelMeta.has(m.meta, ModelMeta.OPAQUE));
        for (int f = 0; f < 6; f++) assertEquals(ModelMeta.F_OCCLUDES | ModelMeta.F_COVERS | ModelMeta.F_OCCLUDABLE,
                ModelMeta.face(m.meta, f));
        // A slab: the top sits half a block deep, the sides cover half.
        long[][] slab = FaceBaker.bake(cube(0.5f), (u, v) -> 0xFF336699);
        BakedModel s = new BakedModel(slab, false, false, false, false, 0, 0, -1, true, 0);
        assertFalse(ModelMeta.has(s.meta, ModelMeta.OPAQUE));
        assertEquals(ModelMeta.F_SELF_LIT | ModelMeta.F_COVERS, ModelMeta.face(s.meta, 1));
        assertEquals(0, ModelMeta.face(s.meta, 0) & ModelMeta.F_SELF_LIT);
        assertEquals(0, ModelMeta.face(s.meta, 2) & ModelMeta.F_COVERS);
    }

    static final class Models implements ModelSource {
        final long[] metas;

        Models(long... metas) {
            this.metas = metas;
        }

        @Override
        public int model(int stateId) {
            return stateId - 1 < metas.length ? stateId - 1 : UNBAKED;
        }

        @Override
        public long meta(int model) {
            return metas[model];
        }
    }

    @Test
    void meshSlab() {
        long opaque = new BakedModel(FaceBaker.bake(cube(1), (u, v) -> -1), false, false, false, true, 0, 0, -1, true, 0).meta;
        Models models = new Models(opaque);
        long[] data = new long[WorldSection.SIZE];
        Arrays.fill(data, Voxel.AIR_SKYLIT);
        // A 32 x 2 x 32 slab of stone at y = 4..5.
        for (int y = 4; y < 6; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++)
            data[WorldSection.index(x, y, z)] = Voxel.of(1, 0, 0);
        Mesher m = new Mesher();
        for (int f = 0; f < 6; f++) m.setNeighbor(f, null, models);
        MeshResult r = m.build(1, data, 0xFF, models);
        // Up and down: 32x32 each in 16x16 pieces = 4 quads; four sides: 32 long, 2 high = 2 quads each.
        assertEquals(4, r.counts[MeshResult.G_FACE + 0]);
        assertEquals(4, r.counts[MeshResult.G_FACE + 1]);
        for (int f = 2; f < 6; f++) assertEquals(2, r.counts[MeshResult.G_FACE + f], "face " + f);
        long up = r.quads[r.start(MeshResult.G_FACE + 1)];
        assertEquals(1, Quad.face(up));
        assertEquals(5, Quad.y(up));
        assertEquals(16, Quad.width(up));
        assertEquals(16, Quad.height(up));
        assertEquals(0x0F, Quad.light(up));   // lit by the sky above
        // Unbaked states abort.
        data[0] = Voxel.of(5, 0, 0);
        assertThrows(Mesher.BakeMiss.class, () -> m.build(1, data, 0xFF, models));
    }
}
