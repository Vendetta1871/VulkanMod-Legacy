package net.vesu.lod;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * The LOD worker threads (AUDIT 5.9): daemon threads {@code Vesu LOD Worker #N} at priority
 * 3. Each pick is weighted-random over the services that have work (ingest 5000,
 * generation reads 1000, save 100, import 3; meshing shares the pool).
 */
public final class WorkerPool {
    public interface Service {
        int weight();

        boolean hasWork();

        /** Runs one job; false if there was none after all. */
        boolean runOne();
    }

    private static final Object MONITOR = new Object();
    private static final List<Service> SERVICES = new CopyOnWriteArrayList<>();
    private static Thread[] threads = new Thread[0];
    private static volatile boolean running;
    private static volatile long wakeups;
    public static volatile Consumer<Throwable> errorHandler = Throwable::printStackTrace;

    private WorkerPool() {}

    public static int defaultThreads() {
        return Math.max((int) (Runtime.getRuntime().availableProcessors() / 1.5), 1);
    }

    public static synchronized void start(int count) {
        if (running && threads.length == count) return;
        stop();
        running = true;
        threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            Thread t = new Thread(WorkerPool::loop, "Vesu LOD Worker #" + (i + 1));
            t.setDaemon(true);
            t.setPriority(3);
            threads[i] = t;
            t.start();
        }
    }

    public static synchronized void stop() {
        running = false;
        signalAll();
        for (Thread t : threads) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads = new Thread[0];
    }

    public static boolean running() {
        return running;
    }

    public static int threadCount() {
        return threads.length;
    }

    public static void register(Service s) {
        SERVICES.add(s);
        signalAll();
    }

    public static void unregister(Service s) {
        SERVICES.remove(s);
    }

    public static void signalAll() {
        synchronized (MONITOR) {
            wakeups++;
            MONITOR.notifyAll();
        }
    }

    /** Picks one service with work, weighted by service weight; null when idle. */
    static Service pick() {
        int total = 0;
        for (Service s : SERVICES) if (s.hasWork()) total += s.weight();
        if (total == 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (Service s : SERVICES) {
            if (!s.hasWork()) continue;
            r -= s.weight();
            if (r < 0) return s;
        }
        return null;
    }

    private static void loop() {
        while (running) {
            long seen;
            synchronized (MONITOR) {
                seen = wakeups;
            }
            Service s = pick();
            boolean ran = false;
            if (s != null) {
                try {
                    ran = s.runOne();
                } catch (Throwable t) {
                    errorHandler.accept(t);
                }
            }
            if (ran) continue;
            synchronized (MONITOR) {
                if (!running) return;
                if (wakeups == seen) {
                    try {
                        MONITOR.wait(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }
}
