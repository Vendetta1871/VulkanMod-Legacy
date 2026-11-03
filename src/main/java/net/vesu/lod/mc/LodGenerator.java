package net.vesu.lod.mc;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.vesu.Initializer;
import net.vesu.lod.LodStorage;

import java.util.HashMap;
import java.util.Map;

/**
 * The generation producer (AUDIT 5.7): singleplayer only, on the integrated server thread
 * within a per-tick time budget. Columns around the player are visited in a spiral, nearest
 * first; a column already in the region files is read by the save importer on a worker,
 * a missing one is generated (with its population neighbours) and snapshotted here.
 * Finished columns are kept in a per-region bitmap in the mappings table (type 3).
 */
final class LodGenerator {
    static final int TYPE_DONE = 3;
    /** Per-tick budget; VESU_LOD_GEN_BUDGET_MS overrides it (tuning, L9). */
    private static final long BUDGET_NS = (long) (Double.parseDouble(
            net.vesu.Env.getOrDefault("VESU_LOD_GEN_BUDGET_MS", "2")) * 1_000_000L);

    private final LodSession session;
    private final LodStorage storage;
    private final Map<Integer, byte[]> done = new HashMap<>();
    private int ring, ringStep, centerX = Integer.MIN_VALUE, centerZ;
    private int generated, read;
    private long backoffUntil;

    LodGenerator(LodSession session, LodStorage storage) {
        this.session = session;
        this.storage = storage;
        storage.forEachMapping((k, v) -> {
            if (k >>> 30 == TYPE_DONE && v.length == 128) done.put(k & ((1 << 30) - 1), v);
        });
    }

    private static int regionKey(int rx, int rz) {
        return (rx & 0x7FFF) << 15 | (rz & 0x7FFF);
    }

    synchronized boolean isDone(int cx, int cz) {
        byte[] b = done.get(regionKey(cx >> 5, cz >> 5));
        int i = (cz & 31) << 5 | (cx & 31);
        return b != null && (b[i >> 3] & 1 << (i & 7)) != 0;
    }

    synchronized void markDone(int cx, int cz) {
        int k = regionKey(cx >> 5, cz >> 5);
        byte[] b = done.computeIfAbsent(k, x -> new byte[128]);
        int i = (cz & 31) << 5 | (cx & 31);
        if ((b[i >> 3] & 1 << (i & 7)) != 0) return;
        b[i >> 3] |= (byte) (1 << (i & 7));
        storage.putMapping(k | TYPE_DONE << 30, b);
    }

    String progress() {
        return String.format("LOD generation: ring %d, %d generated, %d read", ring, generated, read);
    }

    /** One server tick (server thread). */
    void tick(MinecraftServer server) {
        int radius = Initializer.CONFIG.lodGenerationDistance;
        if (radius <= 0) return;
        long now = System.nanoTime();
        if (now < backoffUntil) return;
        // Throttle by the server tick: past 40 ms per tick, pause for a second.
        double mspt = 0;
        for (long t : server.tickTimeArray) mspt += t;
        mspt = mspt / server.tickTimeArray.length / 1e6;
        if (mspt > 40) {
            backoffUntil = now + 1_000_000_000L;
            return;
        }
        if (session.ingest.full() || session.world.saveService().backlogged()) return;
        WorldServer world = server.getWorld(session.dimension);
        if (world == null || world.playerEntities.isEmpty()) return;
        EntityPlayerMP player = (EntityPlayerMP) world.playerEntities.get(0);
        int px = (int) Math.floor(player.posX) >> 4, pz = (int) Math.floor(player.posZ) >> 4;
        if (centerX == Integer.MIN_VALUE || Math.abs(px - centerX) + Math.abs(pz - centerZ) > 16) {
            centerX = px;
            centerZ = pz;
            ring = 0;
            ringStep = 0;
        }
        ChunkProviderServer provider = world.getChunkProvider();
        long deadline = now + BUDGET_NS;
        boolean didWork = false;
        while (ring <= radius && (System.nanoTime() < deadline || !didWork)) {
            int n = ring == 0 ? 1 : ring * 8;
            if (ringStep >= n) {
                ring++;
                ringStep = 0;
                continue;
            }
            int[] c = ringPos(ring, ringStep++);
            int cx = centerX + c[0], cz = centerZ + c[1];
            if (isDone(cx, cz)) continue;
            if (session.ingest.full()) break;
            if (provider.isChunkGeneratedAt(cx, cz) && provider.getLoadedChunk(cx, cz) == null) {
                // On disk: a worker reads it from the region file; the server never loads it.
                session.readsImporter().readChunkLater(this, cx, cz);
                read++;
                continue;
            }
            didWork = true;
            // Population of (x, z) needs its +x, +z and +x+z neighbours.
            Chunk ch = provider.provideChunk(cx, cz);
            provider.provideChunk(cx + 1, cz);
            provider.provideChunk(cx, cz + 1);
            provider.provideChunk(cx + 1, cz + 1);
            if (ch.isTerrainPopulated()) {
                session.ingest(RawChunk.grab(ch, world.provider.hasSkyLight(), 0xFFFF));
                markDone(cx, cz);
                generated++;
            }
            unloadIfFar(world, provider, cx, cz);
            unloadIfFar(world, provider, cx + 1, cz);
            unloadIfFar(world, provider, cx, cz + 1);
            unloadIfFar(world, provider, cx + 1, cz + 1);
        }
    }

    private static void unloadIfFar(WorldServer world, ChunkProviderServer provider, int cx, int cz) {
        Chunk c = provider.getLoadedChunk(cx, cz);
        if (c == null) return;
        if (!world.getPlayerChunkMap().contains(cx, cz)) provider.queueUnload(c);
    }

    /** Position {@code i} on the square ring of radius {@code r} (8r positions). */
    private static int[] ringPos(int r, int i) {
        if (r == 0) return new int[]{0, 0};
        int side = 2 * r;
        int s = i / side, o = i % side;
        return switch (s) {
            case 0 -> new int[]{-r + o, -r};
            case 1 -> new int[]{r, -r + o};
            case 2 -> new int[]{r - o, r};
            default -> new int[]{-r, r - o};
        };
    }
}
