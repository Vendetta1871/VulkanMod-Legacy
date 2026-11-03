package net.vesu.lod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    private static final Mip.Opacity OPACITY = id -> id == 0 ? 0 : id % 16;

    @Test
    void keyRoundTrip() {
        int[][] cases = {{0, 0, 0, 0}, {4, -1, -3, 7}, {2, 8388607, 127, -8388608}, {1, -12345, -128, 54321}};
        for (int[] c : cases) {
            long k = SectionKey.of(c[0], c[1], c[2], c[3]);
            assertEquals(c[0], SectionKey.lvl(k));
            assertEquals(c[1], SectionKey.x(k));
            assertEquals(c[2], SectionKey.y(k));
            assertEquals(c[3], SectionKey.z(k));
            assertEquals(0, k & 0xF);
        }
        long k = SectionKey.of(1, -3, 2, 5);
        for (int o = 0; o < 8; o++) {
            long c = SectionKey.child(k, o);
            assertEquals(k, SectionKey.parent(c));
            assertEquals(o, SectionKey.octantInParent(c));
        }
    }

    @Test
    void voxelFields() {
        long v = Voxel.of(0xABCDE, 0x155, 0x9C);
        assertEquals(0xABCDE, Voxel.state(v));
        assertEquals(0x155, Voxel.biome(v));
        assertEquals(0x9C, Voxel.light(v));
        assertEquals(9, Voxel.blockLight(v));
        assertEquals(12, Voxel.skyLight(v));
        assertEquals(0, v & ((1L << 27) - 1));
        assertTrue(Voxel.isAir(Voxel.of(0, 3, 0xFF)));
    }

    @Test
    void mipRule() {
        long[] c = new long[8];
        for (int i = 0; i < 8; i++) c[i] = Voxel.of(0, 0, (i & 3) << 4 | (i & 1));
        // All air: block floor(avg), sky ceil(avg).
        long m = Mip.mip(c, 0, 4, 2, OPACITY);
        assertTrue(Voxel.isAir(m));
        assertEquals((0 + 1 + 2 + 3 + 0 + 1 + 2 + 3) / 8, Voxel.blockLight(m));
        assertEquals(1, Voxel.skyLight(m));
        // Highest opacity wins; ties to the higher index (x | y<<1 | z<<2, rows 2 apart, layers 4).
        c[0] = Voxel.of(3, 1, 0);   // opacity 3
        c[5] = Voxel.of(19, 2, 0);  // opacity 3, index 5 -> wins the tie
        c[6] = Voxel.of(2, 3, 0);   // opacity 2
        long w = Mip.mip(c, 0, 4, 2, OPACITY);
        assertEquals(19, Voxel.state(w));
        assertEquals(2, Voxel.biome(w));
    }

    @Test
    void codecRoundTrip() {
        Random r = new Random(1);
        long[] data = new long[WorldSection.SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = r.nextInt(10) < 7 ? Voxel.AIR_SKYLIT : Voxel.of(1 + r.nextInt(300), r.nextInt(5), r.nextInt(256));
        }
        long key = SectionKey.of(2, -5, 1, 9);
        byte[] blob = SectionCodec.serialize(key, data, 0xA5);
        assertEquals(SectionCodec.FORMAT, blob[0]);
        long[] back = new long[WorldSection.SIZE];
        SectionCodec.Header h = SectionCodec.deserialize(java.nio.ByteBuffer.wrap(blob), back);
        assertNotNull(h);
        assertEquals(key, h.key());
        assertEquals(0xA5, h.childMask());
        assertArrayEquals(data, back);
    }

    private static SectionSnapshot solid(int x, int y, int z, int state, int height) {
        SectionSnapshot s = new SectionSnapshot().set(x, y, z);
        for (int i = 0; i < 4096; i++) {
            s.data[i] = (i >> 8) < height ? Voxel.of(state, 1, 0) : Voxel.AIR_SKYLIT;
        }
        s.finish(OPACITY);
        return s;
    }

    @Test
    void insertPropagates() {
        MemoryStorage st = new MemoryStorage();
        try (LodWorld w = new LodWorld(st, k -> 15)) {
            int[] events = new int[3];
            w.setDirtyListener((s, flags, nb) -> {
                if ((flags & LodWorld.BLOCK) != 0) events[0]++;
                if ((flags & LodWorld.CHILD_EXISTENCE) != 0) events[1]++;
            });
            w.insert(solid(3, 2, -1, 7, 4));
            // Level 0 section of chunk section (3,2,-1) is (1,1,-1); its voxel (3&1)*16.. holds the data.
            WorldSection l0 = w.acquire(0, 1, 1, -1);
            assertEquals(16 * 16 * 4, l0.nonAirCount());
            assertEquals(0xFF, l0.childMask());
            assertEquals(7, Voxel.state(l0.data[WorldSection.index(16, 0, 16)]));
            assertTrue(Voxel.isAir(l0.data[WorldSection.index(16, 4, 16)]));
            l0.release();
            for (int lvl = 1; lvl <= 4; lvl++) {
                WorldSection s = w.acquire(lvl, 3 >> (lvl + 1), 2 >> (lvl + 1), -1 >> (lvl + 1));
                assertFalse(s.isEmpty(), "level " + lvl);
                s.release();
            }
            assertEquals(5, events[0]);
            assertEquals(5, events[1]);
            // The same data again changes nothing.
            w.insert(solid(3, 2, -1, 7, 4));
            assertEquals(5, events[0]);
            // Clearing it flips emptiness all the way up.
            w.insert(solid(3, 2, -1, 7, 0));
            WorldSection top = w.acquire(4, 0, 0, -1);
            assertTrue(top.isEmpty());
            top.release();
            w.flush();
        }
        assertTrue(st.sectionCount() >= 5);
    }

    @Test
    void lmdbPersists(@TempDir File dir) {
        try (LodWorld w = new LodWorld(new LmdbStorage(dir), k -> 15)) {
            assertEquals(1, w.registry.stateId("minecraft:stone"));
            assertEquals(2, w.registry.stateId("minecraft:dirt"));
            assertEquals(0, w.registry.biomeId("minecraft:plains"));
            w.insert(solid(0, 0, 0, 1, 16));
            w.flush();
        }
        try (LodWorld w = new LodWorld(new LmdbStorage(dir), k -> 15)) {
            assertEquals(2, w.registry.stateId("minecraft:dirt"));
            assertEquals("minecraft:stone", w.registry.stateKey(1));
            assertEquals(0, w.registry.lookupState("minecraft:air"));
            WorldSection s = w.acquire(0, 0, 0, 0);
            assertEquals(4096, s.nonAirCount());
            assertEquals(1, Voxel.state(s.data[0]));
            s.release();
            WorldSection top = w.acquire(4, 0, 0, 0);
            assertFalse(top.isEmpty());
            top.release();
        }
    }

    @Test
    void concurrentAcquire() throws Exception {
        try (LodWorld w = new LodWorld(new MemoryStorage(), k -> 15)) {
            Thread[] t = new Thread[8];
            for (int i = 0; i < t.length; i++) {
                t[i] = new Thread(() -> {
                    Random r = new Random();
                    for (int j = 0; j < 20000; j++) {
                        WorldSection s = w.acquire(0, r.nextInt(8), 0, r.nextInt(8));
                        if (!s.loaded()) throw new AssertionError("not loaded");
                        s.release();
                    }
                });
                t[i].start();
            }
            for (Thread x : t) x.join();
            assertEquals(0, w.primaryCached());
        }
    }

    @Test
    void longMap() {
        LongMap<Integer> m = new LongMap<>();
        Random r = new Random(3);
        java.util.HashMap<Long, Integer> ref = new java.util.HashMap<>();
        for (int i = 0; i < 20000; i++) {
            long k = r.nextInt(3000);
            if (r.nextBoolean()) {
                assertEquals(ref.put(k, i), m.put(k, i));
            } else {
                assertEquals(ref.remove(k), m.remove(k));
            }
            assertEquals(ref.size(), m.size());
        }
        for (var e : ref.entrySet()) assertEquals(e.getValue(), m.get(e.getKey()));
    }
}
