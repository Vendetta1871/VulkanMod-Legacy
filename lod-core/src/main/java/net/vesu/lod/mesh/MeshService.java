package net.vesu.lod.mesh;

import net.vesu.lod.LodWorld;
import net.vesu.lod.SectionKey;
import net.vesu.lod.Voxel;
import net.vesu.lod.WorkerPool;
import net.vesu.lod.WorldSection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Mesh builds on the worker pool (AUDIT 5.13), at most 10 at once. Tasks for one key merge.
 * A build that meets an unbaked state asks the bakery and waits for {@link #onBaked}; the
 * second miss asks for every state of the section and its neighbours. Fewer attempts and
 * a smaller caller priority (e.g. distance) go first.
 */
public final class MeshService implements WorkerPool.Service {
    public static final int MAX_RUNNING = 10;

    private static final class Task implements Comparable<Task> {
        final long key;
        volatile int priority;
        int attempts;
        final long seq;

        Task(long key, int priority, long seq) {
            this.key = key;
            this.priority = priority;
            this.seq = seq;
        }

        @Override
        public int compareTo(Task o) {
            int a = Integer.compare(Math.min(attempts, 3), Math.min(o.attempts, 3));
            if (a != 0) return a;
            a = Integer.compare(priority, o.priority);
            return a != 0 ? a : Long.compare(seq, o.seq);
        }
    }

    private final LodWorld world;
    private final ModelSource models;
    private final Consumer<MeshResult> sink;
    private final IntConsumer bakeRequest;
    private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<Long, Task> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Task> waiting = new ConcurrentLinkedQueue<>();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicInteger failed = new AtomicInteger();
    private static final ThreadLocal<Mesher> MESHER = ThreadLocal.withInitial(Mesher::new);

    public MeshService(LodWorld world, ModelSource models, Consumer<MeshResult> sink, IntConsumer bakeRequest) {
        this.world = world;
        this.models = models;
        this.sink = sink;
        this.bakeRequest = bakeRequest;
    }

    /** Queues a build of {@code key}; an already queued build only takes the better priority. */
    public void submit(long key, int priority) {
        Task t = new Task(key, priority, seq.incrementAndGet());
        Task old = pending.putIfAbsent(key, t);
        if (old != null) {
            if (priority < old.priority) {
                if (queue.remove(old)) {
                    old.priority = priority;
                    queue.add(old);
                }
            }
            return;
        }
        queue.add(t);
        WorkerPool.signalAll();
    }

    /** The bakery finished a batch: waiting builds go back to the queue. */
    public void onBaked() {
        Task t;
        boolean any = false;
        while ((t = waiting.poll()) != null) {
            queue.add(t);
            any = true;
        }
        if (any) WorkerPool.signalAll();
    }

    public int queued() {
        return queue.size() + waiting.size();
    }

    public int failedBuilds() {
        return failed.get();
    }

    @Override
    public int weight() {
        return 2000;
    }

    @Override
    public boolean hasWork() {
        return !queue.isEmpty() && running.get() < MAX_RUNNING;
    }

    @Override
    public boolean runOne() {
        if (running.incrementAndGet() > MAX_RUNNING) {
            running.decrementAndGet();
            return false;
        }
        try {
            Task t = queue.poll();
            if (t == null) return false;
            pending.remove(t.key, t);
            build(t);
            return true;
        } finally {
            running.decrementAndGet();
        }
    }

    private void build(Task t) {
        if (world.closed()) return;
        Mesher mesher = MESHER.get();
        WorldSection s = world.acquire(t.key);
        WorldSection[] nb = new WorldSection[6];
        try {
            for (int f = 0; f < 6; f++) nb[f] = world.acquire(SectionKey.neighbor(t.key, f));
            try {
                for (int f = 0; f < 6; f++) mesher.setNeighbor(f, nb[f].data, models);
                MeshResult r = mesher.build(t.key, s.data, s.childMask(), models);
                sink.accept(r);
            } catch (Mesher.BakeMiss miss) {
                failed.incrementAndGet();
                t.attempts++;
                if (t.attempts >= 2) {
                    requestAll(s.data);
                    for (WorldSection n : nb) requestAll(n.data);
                } else {
                    bakeRequest.accept(miss.stateId);
                }
                // A newer submit for the key replaces this retry.
                if (pending.putIfAbsent(t.key, t) == null) waiting.add(t);
            }
        } finally {
            for (WorldSection n : nb) if (n != null) n.release();
            s.release();
        }
    }

    private void requestAll(long[] data) {
        int last = -1;
        for (long v : data) {
            int st = Voxel.state(v);
            if (st == 0 || st == last) continue;
            last = st;
            if (models.model(st) == ModelSource.UNBAKED) bakeRequest.accept(st);
        }
    }

    /** Drops everything queued (world change). */
    public void clear() {
        queue.clear();
        waiting.clear();
        pending.clear();
    }
}
