package net.vulkanmod.debug;

import net.vulkanmod.Initializer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Lightweight per-frame phase timer for locating CPU cost. Enabled with VULKANMOD_PROFILE=1.
 * Splits each frame into tick+setup, world render (and within it: sync, sky, clouds, entities,
 * the chunk-layer pass), the HUD, and present, logging running averages every
 * {@link #LOG_EVERY} frames.
 *
 * <p>Client-thread only; all timings are wall-clock nanoseconds accumulated and averaged.
 * Nesting: frame = tick + render + present + other; render = sync + world + gui + misc;
 * world = sky + clouds + entities + chunks + (visibility/setup/misc).</p>
 */
public class FrameProfiler {

    public static final boolean ENABLED = System.getenv("VULKANMOD_PROFILE") != null;

    private static final int LOG_EVERY = 120;

    private static long frameStart, renderStart, presentStart, terrainStart, syncStart;
    private static long worldStart, skyStart, cloudsStart, entitiesStart, guiStart;
    private static long tickNs, renderNs, terrainNs, presentNs, syncNs, totalNs, count;
    private static long worldNs, skyNs, cloudsNs, entitiesNs, guiNs;
    private static long entityDrawNs, entityRebuildNs, entityDrawStart, entityRebuildStart;
    private static long entityDraws, entityVerts, entityCount;
    private static long chunkRecordNs, chunkRecordStart, chunkRecords;
    private static long submitNs, submitStart, submitCount;
    private static long layerStartNs, layerStartStart, texNs, texStart;

    // Slow-frame forensics: per-frame phase snapshots + GC attribution, to pin down periodic
    // micro-freezes (which the averaged [profile] line would otherwise blur out).
    private static long lastTickNs, lastSyncNs, lastRenderNs, lastWorldNs, lastSkyNs, lastGuiNs, lastPresentNs;
    private static long gcCount0, gcTime0, maxFrameNs;
    private static final long SLOW_NS = slowNs();

    private static long slowNs() {
        String s = System.getenv("VULKANMOD_SLOW_MS");
        return (s != null ? Long.parseLong(s) : 50L) * 1_000_000L;
    }

    private static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long v = b.getCollectionCount();
            if (v > 0) c += v;
        }
        return c;
    }

    private static long gcTime() {
        long t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long v = b.getCollectionTime();
            if (v > 0) t += v;
        }
        return t;
    }

    /** Times the per-layer texture bridge (lightmap refresh + atlas re-download when animated). */
    public static void texBegin() {
        if (!ENABLED) return;
        texStart = System.nanoTime();
    }

    public static void texEnd() {
        if (!ENABLED) return;
        texNs += System.nanoTime() - texStart;
    }

    /** Times the per-layer pass setup (snapshotState GL queries + texture refresh + camera capture). */
    public static void layerStartBegin() {
        if (!ENABLED) return;
        layerStartStart = System.nanoTime();
    }

    public static void layerStartEnd() {
        if (!ENABLED) return;
        layerStartNs += System.nanoTime() - layerStartStart;
    }

    /** Times the per-layer indirect submission (bindPipeline + UBO upload + drawIndexedIndirect). */
    public static void submitBegin() {
        if (!ENABLED) return;
        submitCount++;
        submitStart = System.nanoTime();
    }

    public static void submitEnd() {
        if (!ENABLED) return;
        submitNs += System.nanoTime() - submitStart;
    }

    /** Times the batched per-section record (matrix derive + SSBO/indirect write). */
    public static void chunkRecordBegin() {
        if (!ENABLED) return;
        chunkRecords++;
        chunkRecordStart = System.nanoTime();
    }

    public static void chunkRecordEnd() {
        if (!ENABLED) return;
        chunkRecordNs += System.nanoTime() - chunkRecordStart;
    }

    /** Counts a rendered entity (RenderManager.renderEntity). */
    public static void entity() {
        if (!ENABLED) return;
        entityCount++;
    }

    /** Accumulates the vertex count captured in one entity model-part draw. */
    public static void entityVerts(int n) {
        if (!ENABLED) return;
        entityVerts += n;
    }

    /** Times the capture+upload+draw half of an entity model-part draw. */
    public static void entityDrawBegin() {
        if (!ENABLED) return;
        entityDraws++;
        entityDrawStart = System.nanoTime();
    }

    public static void entityDrawEnd() {
        if (!ENABLED) return;
        entityDrawNs += System.nanoTime() - entityDrawStart;
    }

    /** Times the model-box rebuild (ModelRenderer rebuilding display-list geometry per part). */
    public static void entityRebuildBegin() {
        if (!ENABLED) return;
        entityRebuildStart = System.nanoTime();
    }

    public static void entityRebuildEnd() {
        if (!ENABLED) return;
        entityRebuildNs += System.nanoTime() - entityRebuildStart;
    }

    public static void frameBegin() {
        if (!ENABLED) return;
        frameStart = System.nanoTime();
        gcCount0 = gcCount();
        gcTime0 = gcTime();
    }

    public static void renderBegin() {
        if (!ENABLED) return;
        long now = System.nanoTime();
        lastTickNs = now - frameStart;
        tickNs += lastTickNs;
        renderStart = now;
    }

    public static void renderEnd() {
        if (!ENABLED) return;
        long d = System.nanoTime() - renderStart;
        lastRenderNs = d;
        renderNs += d;
    }

    /** Times Initializer.beginFrame (fence wait + swapchain acquire) - the GPU backpressure. */
    public static void syncBegin() {
        if (!ENABLED) return;
        syncStart = System.nanoTime();
    }

    public static void syncEnd() {
        if (!ENABLED) return;
        long d = System.nanoTime() - syncStart;
        lastSyncNs = d;
        syncNs += d;
    }

    public static void worldBegin() {
        if (!ENABLED) return;
        worldStart = System.nanoTime();
    }

    public static void worldEnd() {
        if (!ENABLED) return;
        long d = System.nanoTime() - worldStart;
        lastWorldNs = d;
        worldNs += d;
    }

    public static void skyBegin() {
        if (!ENABLED) return;
        skyStart = System.nanoTime();
    }

    public static void skyEnd() {
        if (!ENABLED) return;
        long d = System.nanoTime() - skyStart;
        lastSkyNs = d;
        skyNs += d;
    }

    public static void cloudsBegin() {
        if (!ENABLED) return;
        cloudsStart = System.nanoTime();
    }

    public static void cloudsEnd() {
        if (!ENABLED) return;
        cloudsNs += System.nanoTime() - cloudsStart;
    }

    public static void entitiesBegin() {
        if (!ENABLED) return;
        entitiesStart = System.nanoTime();
    }

    public static void entitiesEnd() {
        if (!ENABLED) return;
        entitiesNs += System.nanoTime() - entitiesStart;
    }

    public static void guiBegin() {
        if (!ENABLED) return;
        guiStart = System.nanoTime();
    }

    public static void guiEnd() {
        if (!ENABLED) return;
        long d = System.nanoTime() - guiStart;
        lastGuiNs = d;
        guiNs += d;
    }

    public static void terrainBegin() {
        if (!ENABLED) return;
        terrainStart = System.nanoTime();
    }

    public static void terrainEnd() {
        if (!ENABLED) return;
        terrainNs += System.nanoTime() - terrainStart;
    }

    public static void presentBegin() {
        if (!ENABLED) return;
        presentStart = System.nanoTime();
    }

    public static void presentEnd() {
        if (!ENABLED) return;
        long now = System.nanoTime();
        long frame = now - frameStart;
        lastPresentNs = now - presentStart;
        presentNs += lastPresentNs;
        totalNs += frame;
        if (frame > maxFrameNs) {
            maxFrameNs = frame;
        }
        if (frame > SLOW_NS) {
            Initializer.LOGGER.info(
                    "[slow] total={}ms gc={}ms/{} tick={} sync={} render={} world={} sky={} gui={} present={}",
                    fmt(frame / 1e6), gcTime() - gcTime0, gcCount() - gcCount0,
                    fmt(lastTickNs / 1e6), fmt(lastSyncNs / 1e6), fmt(lastRenderNs / 1e6),
                    fmt(lastWorldNs / 1e6), fmt(lastSkyNs / 1e6), fmt(lastGuiNs / 1e6), fmt(lastPresentNs / 1e6));
        }
        if (++count % LOG_EVERY == 0) {
            double n = LOG_EVERY;
            Initializer.LOGGER.info("[profile] frame={}ms max={} tick={} render={} [sync={} world={} (sky={} ent={} [draw={} rebuild={} ent/f={} draws/f={} v/draw={}] chunks={} rec={}/{}s sub={} lstart={} tex={}) gui={}] present={} fps~{}",
                    fmt(totalNs / n / 1e6), fmt(maxFrameNs / 1e6), fmt(tickNs / n / 1e6), fmt(renderNs / n / 1e6),
                    fmt(syncNs / n / 1e6), fmt(worldNs / n / 1e6),
                    fmt(skyNs / n / 1e6), fmt(entitiesNs / n / 1e6),
                    fmt(entityDrawNs / n / 1e6), fmt(entityRebuildNs / n / 1e6),
                    fmt(entityCount / n), fmt(entityDraws / n), fmt(entityVerts / (double) Math.max(1, entityDraws)),
                    fmt(terrainNs / n / 1e6), fmt(chunkRecordNs / n / 1e6), fmt(chunkRecords / n), fmt(submitNs / n / 1e6), fmt(layerStartNs / n / 1e6), fmt(texNs / n / 1e6), fmt(guiNs / n / 1e6), fmt(presentNs / n / 1e6),
                    fmt(n * 1e9 / totalNs));
            tickNs = renderNs = terrainNs = presentNs = syncNs = totalNs = 0;
            worldNs = skyNs = cloudsNs = entitiesNs = guiNs = 0;
            entityDrawNs = entityRebuildNs = entityDraws = entityVerts = entityCount = 0;
            chunkRecordNs = chunkRecords = 0;
            submitNs = submitCount = layerStartNs = texNs = 0;
            maxFrameNs = 0;
        }
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
