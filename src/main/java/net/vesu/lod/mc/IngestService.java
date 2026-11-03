package net.vesu.lod.mc;

import net.vesu.lod.LodWorld;
import net.vesu.lod.SaveService;
import net.vesu.lod.SectionSnapshot;
import net.vesu.lod.WorkerPool;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Live ingest (AUDIT 5.6): chunk copies from the client thread, voxelized on workers. */
final class IngestService implements WorkerPool.Service {
    private final LodWorld world;
    private final StateMapper mapper;
    private final ConcurrentLinkedQueue<RawChunk> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private static final ThreadLocal<SectionSnapshot> SNAPSHOT = ThreadLocal.withInitial(SectionSnapshot::new);

    IngestService(LodWorld world, StateMapper mapper) {
        this.world = world;
        this.mapper = mapper;
    }

    void add(RawChunk c) {
        queue.add(c);
        size.incrementAndGet();
        WorkerPool.signalAll();
    }

    int queued() {
        return size.get();
    }

    /** Chunk copies are ~100 KB each: producers that can wait do so past this many. */
    boolean full() {
        return size.get() >= 128;
    }

    @Override
    public int weight() {
        return 5000;
    }

    @Override
    public boolean hasWork() {
        // Saving must keep up: past the blocking limit ingest waits for the save queue.
        return size.get() > 0 && !world.saveService().backlogged();
    }

    @Override
    public boolean runOne() {
        RawChunk c = queue.poll();
        if (c == null) return false;
        size.decrementAndGet();
        if (world.closed()) return true;
        c.insertInto(world, mapper, SNAPSHOT.get());
        return true;
    }

    void clear() {
        queue.clear();
        size.set(0);
    }
}
