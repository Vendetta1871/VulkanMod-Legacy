package net.vesu.lod.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.vesu.Initializer;
import net.vesu.lod.LodWorld;
import net.vesu.lod.WorkerPool;
import net.vesu.vulkan.FrameTargets;
import net.vesu.vulkan.VRenderSystem;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The 1.12.2 side of the LOD terrain (AUDIT 5.2, net.vesu.lod.mc): world identity, live
 * ingest hooks, the per-frame render hooks, F3 lines. One {@link LodSession} per client
 * world while LODs are on; none exists, and no thread runs, while they are off.
 */
public final class LodSystem {
    private static LodSession session;
    private static boolean fatal;
    private static WorldClient sessionWorld;
    /** Block edits per chunk section, coalesced: section key -> due time (ms). */
    private static final Map<Long, Long> pendingEdits = new HashMap<>();
    private static final long EDIT_DELAY_MS = 500;

    private LodSystem() {}

    public static void init() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(LodSystem.class);
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new LodCommand());
        LodWorld.LOG = msg -> Initializer.LOGGER.warn("[lod] {}", msg);
        WorkerPool.errorHandler = t -> Initializer.LOGGER.error("[lod] worker failed", t);
        // VESU_LOD_GEN=<chunks>: generation distance for test runs.
        String gen = net.vesu.Env.get("VESU_LOD_GEN");
        if (gen != null) Initializer.CONFIG.lodGenerationDistance = Integer.parseInt(gen.trim());
        // VESU_LOD_DISTANCE=<chunks>: LOD distance for test runs.
        String distance = net.vesu.Env.get("VESU_LOD_DISTANCE");
        if (distance != null) Initializer.CONFIG.lodDistance = Integer.parseInt(distance.trim());
    }

    /** VESU_LOD=0/1 overrides the setting (comparison runs). */
    private static final String ENV = net.vesu.Env.get("VESU_LOD");

    public static boolean enabled() {
        if (ENV != null) return ENV.equals("1") && !fatal;
        return Initializer.CONFIG != null && Initializer.CONFIG.lodEnabled && !fatal;
    }

    static int registryBiomes() {
        LodSession s = session;
        return s == null ? 0 : s.world.registry.biomeCount();
    }

    /** Storage failed: LODs stay off for the rest of the game session (data protection). */
    static void fatal(Throwable t) {
        fatal = true;
        Initializer.LOGGER.error("[lod] storage failed; LODs are off until restart", t);
    }

    /** True while LODs draw this frame: the far plane and fog follow the LOD distance. */
    public static boolean drawing() {
        return session != null && Initializer.CONFIG.lodRendering && VRenderSystem.reverseZ;
    }

    public static double distanceBlocks() {
        return Initializer.CONFIG.lodDistance * 16.0;
    }

    private static File directory(WorldClient world) {
        Minecraft mc = Minecraft.getMinecraft();
        String dim = "DIM" + world.provider.getDimension();
        if (mc.isIntegratedServerRunning() && mc.getIntegratedServer() != null) {
            File save = net.minecraftforge.common.DimensionManager.getCurrentSaveRootDirectory();
            if (save != null) return new File(new File(save, "vesu_lod"), dim);
        }
        String server;
        if (mc.isConnectedToRealms()) {
            server = "realms";
        } else {
            ServerData d = mc.getCurrentServerData();
            server = d == null || d.serverIP == null ? "unknown" : d.serverIP.replace(':', '_');
        }
        server = server.replaceAll("[^A-Za-z0-9._\\-]", "_");
        return new File(new File(new File(mc.gameDir, "vesu_lod"), "servers"), server + File.separator + dim);
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load e) {
        if (!(e.getWorld() instanceof WorldClient world)) return;
        close();
        atlasDirty = false;
        if (!enabled()) return;
        int threads = Initializer.CONFIG.lodThreads > 0 ? Initializer.CONFIG.lodThreads : WorkerPool.defaultThreads();
        WorkerPool.start(threads);
        try {
            session = new LodSession(directory(world), world.provider.getDimension(), world.provider.hasSkyLight());
            sessionWorld = world;
            world.addEventListener(new LodWorldListener());
            downloadAtlas();
            // VESU_LOD_IMPORT=<save name>: import that save on start (autotests, benchmarks).
            String imp = net.vesu.Env.get("VESU_LOD_IMPORT");
            if (imp != null && !imp.isEmpty()) importWorld(imp);
        } catch (RuntimeException | LinkageError t) {
            Initializer.LOGGER.error("[lod] cannot start", t);
            session = null;
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload e) {
        if (e.getWorld() instanceof WorldClient && e.getWorld() == sessionWorld) close();
    }

    /** Settings changed: start or stop to match them. */
    public static void applySettings() {
        Minecraft mc = Minecraft.getMinecraft();
        net.vesu.shader.PackManager.lodChanged();
        if (!enabled()) {
            close();
            WorkerPool.stop();
        } else if (session == null && mc.world != null) {
            onWorldLoad(new WorldEvent.Load(mc.world));
            importLoaded();
        }
    }

    static void close() {
        pendingEdits.clear();
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException t) {
                Initializer.LOGGER.error("[lod] close failed", t);
            }
        }
        session = null;
        sessionWorld = null;
    }

    /** The block atlas, read back from GL for the bakery. Client thread. */
    static void downloadAtlas() {
        LodSession s = session;
        if (s == null) return;
        int id = Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId();
        net.minecraft.client.renderer.GlStateManager.bindTexture(id);
        int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
        int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) return;
        java.nio.ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
        try {
            org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
            int[] argb = new int[w * h];
            for (int i = 0; i < argb.length; i++) {
                int abgr = pixels.getInt(i * 4);
                argb[i] = (abgr & 0xFF00FF00) | (abgr >> 16 & 0xFF) | (abgr & 0xFF) << 16;
            }
            s.bakery.setAtlas(argb, w, h);
            Initializer.LOGGER.info("[lod] block atlas {}x{} read for baking", w, h);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(pixels);
        }
    }

    // ---- Ingest hooks (client thread) ----

    public static void onChunkData(Chunk c) {
        LodSession s = session;
        if (s == null || c == null || c.isEmpty()) return;
        s.ingest(RawChunk.grab(c, s.hasSky(), 0xFFFF));
    }

    public static void onChunkUnload(Chunk c) {
        onChunkData(c);
    }

    static void onBlockChanged(int x, int y, int z) {
        if (session == null || y < 0 || y > 255) return;
        long due = System.currentTimeMillis() + EDIT_DELAY_MS;
        schedule(x >> 4, y >> 4, z >> 4, due);
        // A change on a section border also changes what the neighbour culls.
        if ((x & 15) == 0) schedule((x >> 4) - 1, y >> 4, z >> 4, due);
        if ((x & 15) == 15) schedule((x >> 4) + 1, y >> 4, z >> 4, due);
        if ((y & 15) == 0 && y > 0) schedule(x >> 4, (y >> 4) - 1, z >> 4, due);
        if ((y & 15) == 15 && y < 255) schedule(x >> 4, (y >> 4) + 1, z >> 4, due);
        if ((z & 15) == 0) schedule(x >> 4, y >> 4, (z >> 4) - 1, due);
        if ((z & 15) == 15) schedule(x >> 4, y >> 4, (z >> 4) + 1, due);
    }

    private static void schedule(int cx, int cy, int cz, long due) {
        long k = (long) cx << 36 | (long) (cz & 0xFFFFFFF) << 8 | cy;
        pendingEdits.putIfAbsent(k, due);
    }

    /**
     * The band where vanilla terrain dissolves into the LODs instead of ending at a chunk
     * border: it ends a chunk inside the render distance, where every column is still built.
     */
    static float[] edgeBand(int renderDistance) {
        if (renderDistance < 4) return null;
        float end = (renderDistance - 1) * 16f;
        return new float[]{end - 16f, end};
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        LodSession s = session;
        Minecraft mc = Minecraft.getMinecraft();
        VRenderSystem.lodFarPlane = s != null && Initializer.CONFIG.lodRendering ? (float) (distanceBlocks() + 512.0) : 0.0f;
        VRenderSystem.fogScaleFrom = mc.gameSettings.renderDistanceChunks * 16.0f;
        boolean scaleFog = s != null && Initializer.CONFIG.lodRendering && Initializer.CONFIG.lodFog.startsWith("fog");
        VRenderSystem.fogScale = scaleFog ? (float) (distanceBlocks() / VRenderSystem.fogScaleFrom) : 1.0f;
        float[] band = s != null && Initializer.CONFIG.lodRendering ? edgeBand(mc.gameSettings.renderDistanceChunks) : null;
        VRenderSystem.lodEdgeStart = band == null ? 0 : band[0];
        VRenderSystem.lodEdgeEnd = band == null ? 0 : band[1];
        if (s != null && atlasDirty) {
            atlasDirty = false;
            downloadAtlas();
        }
        if (s == null || mc.world == null || pendingEdits.isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<Long, Integer> chunks = new HashMap<>();
        Iterator<Map.Entry<Long, Long>> it = pendingEdits.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> en = it.next();
            if (en.getValue() > now) continue;
            long k = en.getKey();
            it.remove();
            long chunk = k & ~0xFFL;
            chunks.merge(chunk, 1 << (int) (k & 0xFF), (a, b) -> a | b);
        }
        for (Map.Entry<Long, Integer> en : chunks.entrySet()) {
            long k = en.getKey();
            int cx = (int) (k >> 36), cz = (int) (k << 28 >> 36);
            Chunk c = mc.world.getChunkProvider().getLoadedChunk(cx, cz);
            if (c == null || c.isEmpty()) continue;
            s.ingest(RawChunk.grab(c, s.hasSky(), en.getValue()));
        }
    }

    /** {@code /vesu lod import current}: every loaded chunk. */
    static int importLoaded() {
        LodSession s = session;
        Minecraft mc = Minecraft.getMinecraft();
        if (s == null || mc.world == null) return 0;
        int n = 0;
        net.minecraft.client.multiplayer.ChunkProviderClient p = mc.world.getChunkProvider();
        int r = mc.gameSettings.renderDistanceChunks + 2;
        int px = (int) Math.floor(mc.player == null ? 0 : mc.player.posX) >> 4, pz = (int) Math.floor(mc.player == null ? 0 : mc.player.posZ) >> 4;
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            Chunk c = p.getLoadedChunk(px + dx, pz + dz);
            if (c == null || c.isEmpty()) continue;
            s.ingest(RawChunk.grab(c, s.hasSky(), 0xFFFF));
            n++;
        }
        return n;
    }

    /** {@code /vesu lod import world <name>}. */
    static boolean importWorld(String name) {
        LodSession s = session;
        if (s == null) return false;
        File dir = new File(new File(Minecraft.getMinecraft().gameDir, "saves"), name);
        if (!dir.isDirectory()) return false;
        s.startImport(dir);
        return true;
    }

    static void cancelImport() {
        LodSession s = session;
        if (s != null) s.cancelImport();
    }

    static void reload() {
        LodSession s = session;
        if (s == null) return;
        downloadAtlas();
        s.reload();
    }

    // ---- Render hooks ----

    /** Before vanilla's solid layer: distant terrain through the pack's DH program (AUDIT 5.16). */
    public static void renderPack() {
        LodSession s = session;
        // A pack's shadow pass records alongside the whole scene: it says nothing about this draw.
        if (s == null || !Initializer.CONFIG.lodRendering || !net.vesu.vulkan.LodPackBridge.active()
                || !net.vesu.vulkan.Drawer.isActive()) return;
        try {
            s.renderPack();
        } catch (RuntimeException t) {
            Initializer.LOGGER.error("[lod] render failed; LOD drawing stops", t);
            Initializer.CONFIG.lodRendering = false;
        }
    }

    /** After vanilla's cutout layer (RenderGlobal.renderBlockLayer). */
    public static void renderOpaque() {
        LodSession s = session;
        if (s == null || !VRenderSystem.reverseZ || FrameTargets.phase() != FrameTargets.Phase.SCENE
                || net.vesu.vulkan.ShadowPass.active() || !net.vesu.vulkan.Drawer.isActive()) return;
        try {
            s.renderOpaque();
        } catch (RuntimeException t) {
            Initializer.LOGGER.error("[lod] render failed; LOD drawing stops", t);
            Initializer.CONFIG.lodRendering = false;
        }
    }

    /** Before vanilla's translucent layer. */
    public static void renderTranslucent() {
        LodSession s = session;
        if (s != null && net.vesu.vulkan.LodPackBridge.active() && net.vesu.vulkan.Drawer.isActive()) {
            // The pack's DEFERRED passes run first; distant water then draws into its translucent targets.
            net.vesu.vulkan.Drawer.snapshotOpaque();
            net.vesu.vulkan.Drawer.enterTranslucent();
            try {
                s.renderPackTranslucent();
            } catch (RuntimeException t) {
                Initializer.LOGGER.error("[lod] render failed; LOD drawing stops", t);
                Initializer.CONFIG.lodRendering = false;
            }
            return;
        }
        if (s == null || !VRenderSystem.reverseZ || FrameTargets.phase() != FrameTargets.Phase.SCENE
                || net.vesu.vulkan.ShadowPass.active() || !net.vesu.vulkan.Drawer.isActive()) return;
        try {
            s.renderTranslucent();
        } catch (RuntimeException t) {
            Initializer.LOGGER.error("[lod] render failed; LOD drawing stops", t);
            Initializer.CONFIG.lodRendering = false;
        }
    }

    /** Resource reload (F3+T, packs): the atlas is read again on the next tick and the models re-bake. */
    @SubscribeEvent
    public static void onStitch(net.minecraftforge.client.event.TextureStitchEvent.Post e) {
        if (e.getMap() == Minecraft.getMinecraft().getTextureMapBlocks()) atlasDirty = true;
    }

    private static boolean atlasDirty;

    /** The generation producer runs on the integrated server's thread (AUDIT 5.7). */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || Initializer.CONFIG.lodGenerationDistance <= 0) return;
        LodSession s = session;
        Minecraft mc = Minecraft.getMinecraft();
        if (s == null || !mc.isIntegratedServerRunning() || mc.getIntegratedServer() == null) return;
        try {
            LodGenerator g = s.generator();
            if (g != null) g.tick(mc.getIntegratedServer());
        } catch (RuntimeException t) {
            Initializer.LOGGER.error("[lod] generation stopped", t);
            Initializer.CONFIG.lodGenerationDistance = 0;
        }
    }

    @SubscribeEvent
    public static void onDebugText(RenderGameOverlayEvent.Text e) {
        if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) return;
        LodSession s = session;
        if (s == null) {
            e.getRight().add(enabled() ? "LOD idle" : "LOD off");
            return;
        }
        e.getRight().add("");
        e.getRight().addAll(s.debugLines());
    }

    static java.util.List<String> status() {
        LodSession s = session;
        if (s == null) return java.util.List.of(enabled() ? "LOD: no world" : "LOD: off");
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add("LOD store: " + s.dir);
        l.addAll(s.debugLines());
        return l;
    }
}
