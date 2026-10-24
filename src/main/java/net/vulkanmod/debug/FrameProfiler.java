package net.vulkanmod.debug;

import net.vulkanmod.Initializer;

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
    }

    public static void renderBegin() {
        if (!ENABLED) return;
        tickNs += System.nanoTime() - frameStart;
        renderStart = System.nanoTime();
    }

    public static void renderEnd() {
        if (!ENABLED) return;
        renderNs += System.nanoTime() - renderStart;
    }

    /** Times Initializer.beginFrame (fence wait + swapchain acquire) - the GPU backpressure. */
    public static void syncBegin() {
        if (!ENABLED) return;
        syncStart = System.nanoTime();
    }

    public static void syncEnd() {
        if (!ENABLED) return;
        syncNs += System.nanoTime() - syncStart;
    }

    public static void worldBegin() {
        if (!ENABLED) return;
        worldStart = System.nanoTime();
    }

    public static void worldEnd() {
        if (!ENABLED) return;
        worldNs += System.nanoTime() - worldStart;
    }

    public static void skyBegin() {
        if (!ENABLED) return;
        skyStart = System.nanoTime();
    }

    public static void skyEnd() {
        if (!ENABLED) return;
        skyNs += System.nanoTime() - skyStart;
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
        guiNs += System.nanoTime() - guiStart;
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
        presentNs += now - presentStart;
        totalNs += now - frameStart;
        if (++count % LOG_EVERY == 0) {
            double n = LOG_EVERY;
            Initializer.LOGGER.info("[profile] frame={}ms tick={} render={} [sync={} world={} (sky={} ent={} [draw={} rebuild={} ent/f={} draws/f={} v/draw={}] chunks={}) gui={}] present={} fps~{}",
                    fmt(totalNs / n / 1e6), fmt(tickNs / n / 1e6), fmt(renderNs / n / 1e6),
                    fmt(syncNs / n / 1e6), fmt(worldNs / n / 1e6),
                    fmt(skyNs / n / 1e6), fmt(entitiesNs / n / 1e6),
                    fmt(entityDrawNs / n / 1e6), fmt(entityRebuildNs / n / 1e6),
                    fmt(entityCount / n), fmt(entityDraws / n), fmt(entityVerts / (double) Math.max(1, entityDraws)),
                    fmt(terrainNs / n / 1e6), fmt(guiNs / n / 1e6), fmt(presentNs / n / 1e6),
                    fmt(n * 1e9 / totalNs));
            tickNs = renderNs = terrainNs = presentNs = syncNs = totalNs = 0;
            worldNs = skyNs = cloudsNs = entitiesNs = guiNs = 0;
            entityDrawNs = entityRebuildNs = entityDraws = entityVerts = entityCount = 0;
        }
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
