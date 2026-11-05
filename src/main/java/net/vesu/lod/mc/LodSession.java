package net.vesu.lod.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.vesu.Initializer;
import net.vesu.lod.DirtyRouter;
import net.vesu.lod.LmdbStorage;
import net.vesu.lod.LodStorage;
import net.vesu.lod.LodWorld;
import net.vesu.lod.LongMap;
import net.vesu.lod.MemoryStorage;
import net.vesu.lod.SectionKey;
import net.vesu.lod.WorkerPool;
import net.vesu.lod.gpu.LodRenderer;
import net.vesu.lod.gpu.LodTraversal;
import net.vesu.lod.mesh.MeshResult;
import net.vesu.lod.mesh.MeshService;
import net.vesu.render.CameraHistory;
import net.vesu.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One LOD world in use (a dimension of a save or server): store, bakery, meshing, ingest
 * and the render-side node set. The node selection is the first-pixels form (L5): the CPU
 * walks the octree from the top-level sections by projected size each frame and writes
 * indirect draws; the GPU traversal replaces it in L6.
 */
public final class LodSession {
    /** A section the renderer knows: its mesh in the arena and its children. */
    private static final class Node {
        final long key;
        final int lvl, x, y, z;
        int slot = -1;
        long quadStart = -1;
        int quadCount;
        final int[] counts = new int[MeshResult.GROUPS];
        /** -1 until the first mesh arrives. */
        int childMask = -1;
        int lastSeen;
        int requestedAt = -1000;
        /** The frame its mesh arrived: its copy runs after the shadow map's commands. */
        int meshFrame = -1;
        /** The frame a mesh was asked again for the RT scene. */
        int rtAsked = -100000;
        /** Its id in the GPU node table and its block of child ids (0: none). */
        int gpuId, block;

        Node(long key) {
            this.key = key;
            lvl = SectionKey.lvl(key);
            x = SectionKey.x(key);
            y = SectionKey.y(key);
            z = SectionKey.z(key);
        }

        boolean ready() {
            return childMask >= 0;
        }
    }

    final File dir;
    final int dimension;
    final LodWorld world;
    final StateMapper mapper;
    final Bakery bakery;
    final MeshService meshes;
    final IngestService ingest;
    final DirtyRouter router;
    private final ConcurrentLinkedQueue<MeshResult> results = new ConcurrentLinkedQueue<>();
    private final LongMap<Node> nodes = new LongMap<>(4096);
    private LodRenderer renderer;
    private int frame;
    private volatile double camX, camY, camZ;
    private int tintVersion = -1, bakeryResets;
    private int drawnSections, visitedNodes;
    private final boolean sky;

    // Coverage of the sections vanilla has built: (z * n + x) * 16 + y.
    private int[] cover = new int[70 * 70 * 16 / 32 + 2];
    private int coverX, coverZ, coverN;

    LodSession(File dir, int dimension, boolean sky) {
        this.dir = dir;
        this.dimension = dimension;
        this.sky = sky;
        LodStorage storage;
        try {
            storage = new LmdbStorage(dir);
        } catch (RuntimeException | LinkageError e) {
            Initializer.LOGGER.error("[lod] cannot open {}: {}; this session keeps LODs in memory", dir, e.toString());
            storage = new MemoryStorage();
        }
        world = new LodWorld(storage, StateMapper::opacityOf);
        world.setFailureHandler(t -> LodSystem.fatal(t));
        mapper = new StateMapper(world.registry);
        bakery = new Bakery(mapper);
        router = new DirtyRouter(new DirtyRouter.Listener() {
            @Override
            public void onStructure(long key) {
                submit(key);
            }

            @Override
            public void onRemesh(long key) {
                submit(key);
            }
        });
        world.setDirtyListener((s, flags, nb) -> router.dirty(s.key, flags));
        meshes = new MeshService(world, bakery.tables, results::add, bakery::request);
        bakery.setOnBatch(meshes::onBaked);
        world.registry.setListener((id, key) -> bakery.request(id));
        ingest = new IngestService(world, mapper);
        WorkerPool.register(ingest);
        WorkerPool.register(meshes);
        Initializer.LOGGER.info("[lod] opened {} ({} states, {} biomes known)", dir,
                world.registry.stateCount(), world.registry.biomeCount());
    }

    private void submit(long key) {
        int lvl = SectionKey.lvl(key);
        double s = 32 << lvl;
        double dx = (SectionKey.x(key) + 0.5) * s - camX, dz = (SectionKey.z(key) + 0.5) * s - camZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        meshes.submit(key, (int) Math.min(1_000_000, d / s * 64));
    }

    private SaveImporter importer, reads;
    private LodGenerator generator;

    /** Singleplayer: the generation producer and the importer that reads its on-disk columns. */
    LodGenerator generator() {
        if (generator == null) {
            File save = dir.getParentFile() == null ? null : dir.getParentFile().getParentFile();
            if (save == null) return null;
            reads = SaveImporter.forReads(this, save, dimension);
            WorkerPool.register(reads);
            generator = new LodGenerator(this, world.storage());
        }
        return generator;
    }

    SaveImporter readsImporter() {
        return reads;
    }

    /** Starts importing a save's region files for this dimension (AUDIT 5.8). */
    void startImport(File saveDir) {
        cancelImport();
        importer = new SaveImporter(this, saveDir, dimension);
        WorkerPool.register(importer);
    }

    void cancelImport() {
        if (importer != null) {
            importer.cancel();
            WorkerPool.unregister(importer);
            importer = null;
        }
    }

    void close() {
        cancelImport();
        if (reads != null) {
            reads.cancel();
            WorkerPool.unregister(reads);
        }
        WorkerPool.unregister(ingest);
        WorkerPool.unregister(meshes);
        ingest.clear();
        meshes.clear();
        bakery.stop();
        world.close();
        net.vesu.vulkan.rt.RtLod.clear();
        if (renderer != null) renderer.destroy();
        renderer = null;
        Initializer.LOGGER.info("[lod] closed {}", dir);
    }

    // ---- Render side ----

    private final Matrix4f mv = new Matrix4f(), proj = new Matrix4f(), viewProj = new Matrix4f();
    private final Vector4f[] planes = new Vector4f[6];
    private final ByteBuffer ubo = MemoryUtil.memAlloc(256);
    private boolean frameOpen;
    private final List<Node> evict = new ArrayList<>();

    /** Start of a frame's LOD work: uploads of what arrived. */
    private void prepare() {
        if (renderer == null) {
            long budget = (long) Math.max(64, Math.min(1024, Initializer.CONFIG.lodMeshCacheMb / 2)) << 20;
            renderer = new LodRenderer(budget);
        }
        renderer.beginFrame();
        // Before any table change of this frame (results, requests, new nodes).
        LodTraversal trav = traversal();
        if (trav != null) trav.beginFrame(renderer.frameSlot());
        frameOpen = true;
        frame++;
        camX = CameraHistory.camX();
        camY = CameraHistory.camY();
        camZ = CameraHistory.camZ();

        if (bakery.resets != bakeryResets) {
            bakeryResets = bakery.resets;
            reload();
        }
        Bakery.Upload up;
        while ((up = bakery.uploads.poll()) != null) renderer.uploadModel(up);
        if (bakery.tintVersion != tintVersion) {
            tintVersion = bakery.tintVersion;
            renderer.uploadTints(bakery.tintTable);
        }
        applyResults();
        LodTraversal tr = traversal();
        if (tr != null) {
            int n = tr.takeRequests(renderer.frameSlot(), requestIds);
            for (int i = 0; i < n; i++) {
                int id = requestIds[i];
                if (id > 0 && id < byId.length && byId[id] != null) answer(byId[id]);
            }
        }
    }

    private final Matrix4f dhProj = new Matrix4f();
    /** This frame draws through the pack's DH program: the CPU writes the draws, without coverage or SSAO. */
    private boolean packMode;
    /** This frame's pack draws distant terrain through its terrain programs, in the scene depth. */
    private boolean packTerrain;
    /** drawPack ran this frame: the distant water may follow. */
    private boolean packOpen;
    private final org.joml.Matrix4f invViewProj = new org.joml.Matrix4f();

    /**
     * Under a shader pack with DH programs (AUDIT 5.16, contract B), before vanilla's solid
     * layer: the LODs go into the pack's targets with their own projection and depth, and
     * vanilla terrain drawn afterwards covers them where it exists.
     */
    void renderPack() {
        Minecraft mc = Minecraft.getMinecraft();
        prepare();
        VRenderSystem.captureCameraMatrices(mv, proj);
        packTerrain = net.vesu.vulkan.LodPackBridge.throughTerrainPrograms();
        if (packTerrain) {
            // The pack's terrain programs: the scene's projection and depth, as vanilla terrain.
            proj.mul(mv, viewProj);
        } else {
            VRenderSystem.undoDepthRemap(proj, dhProj);
            net.vesu.shader.FrameUniforms.withPlanes(dhProj, net.vesu.shader.FrameUniforms.DH_NEAR_PLANE,
                    net.vesu.shader.FrameUniforms.dhFarPlane(), dhProj);
            VRenderSystem.remapDepth(dhProj, dhProj);
            dhProj.mul(mv, viewProj);
        }
        for (int i = 0; i < 6; i++) {
            if (planes[i] == null) planes[i] = new Vector4f();
            viewProj.frustumPlane(i, planes[i]);
        }
        buildCover(mc);
        candOpaque = 0;
        candTranslucent = 0;
        boolean gpuSelect = traversal() != null;
        packMode = true;
        try {
            if (gpuSelect) selectRoots(mc);
            else select(mc);
        } finally {
            packMode = false;
        }
        if (gpuSelect) {
            // The traversal drops what vanilla covers only where the pack draws LODs in the scene depth.
            writeUniforms(mc, packTerrain);
        }
        renderer.endUploads();
        if (gpuSelect) {
            renderer.traversedCandidates(nodes.size());
            double room = Math.max(0, (4000 - meshes.queued()) / 4000.0);
            int budget = (int) Math.ceil(LodTraversal.MAX_REQUESTS * room * room);
            float px = (float) pixelScale, blocks = (float) lodBlocks;
            int frameId = frame, roots = rootCount;
            renderer.beforePack(() -> {
                // Vanilla terrain is not drawn yet: no occlusion test.
                org.lwjgl.vulkan.VkCommandBuffer cmd = net.vesu.vulkan.Drawer.currentCommandBuffer();
                net.vesu.vulkan.GpuTimer.mark(cmd, net.vesu.vulkan.GpuTimer.LOD_START);
                renderer.recordTraversal(cmd, roots, frameId, px, Initializer.CONFIG.lodQuality, blocks, budget);
                renderer.cull(cmd, 0L, net.vesu.vulkan.FrameTargets.scene.width(), net.vesu.vulkan.FrameTargets.scene.height());
                net.vesu.vulkan.GpuTimer.mark(cmd, net.vesu.vulkan.GpuTimer.LOD_CULL);
            });
        }
        if (!gpuSelect) net.vesu.vulkan.GpuTimer.mark(net.vesu.vulkan.Drawer.currentCommandBuffer(), net.vesu.vulkan.GpuTimer.LOD_CULL);
        int bx = (int) Math.floor(camX), by = (int) Math.floor(camY), bz = (int) Math.floor(camZ);
        packOpen = renderer.drawPack(viewProj, mv, bx, by, bz, (float) (camX - bx), (float) (camY - by), (float) (camZ - bz),
                invViewProj.set(viewProj).invert(), cover, coverX, coverZ, coverN, !net.vesu.vulkan.FrameTargets.packSpace(),
                packTerrain);
        net.vesu.vulkan.GpuTimer.mark(net.vesu.vulkan.Drawer.currentCommandBuffer(), net.vesu.vulkan.GpuTimer.LOD_DRAW);
        renderShadow(mc);
        frameOpen = false;
        if ((frame & 255) == 0) cleanup();
    }

    /** After the vanilla opaque layers: upload what arrived, select, draw the opaque LODs. */
    void renderOpaque() {
        Minecraft mc = Minecraft.getMinecraft();
        prepare();
        VRenderSystem.captureCameraMatrices(mv, proj);
        proj.mul(mv, viewProj);
        for (int i = 0; i < 6; i++) {
            if (planes[i] == null) planes[i] = new Vector4f();
            viewProj.frustumPlane(i, planes[i]);
        }
        buildCover(mc);
        candOpaque = 0;
        candTranslucent = 0;
        rtRing = net.vesu.vulkan.rt.RtScene.enabled() && Initializer.CONFIG.lodRtRing > 0;
        if (rtRing) net.vesu.vulkan.rt.RtLod.setTables(renderer.modelsPtr(), renderer.tintsPtr());
        boolean gpuSelect = traversal() != null;
        if (gpuSelect) {
            selectRoots(mc);
            if (rtRing && frame % 30 == 0) rtRingFromStamps();
        } else {
            select(mc);
        }
        rtRing = false;
        writeUniforms(mc, true);
        renderer.endUploads();
        net.vesu.vulkan.GpuTimer.mark(net.vesu.vulkan.Drawer.currentCommandBuffer(), net.vesu.vulkan.GpuTimer.LOD_START);
        if (renderer.gpuCull) {
            // Translucent candidates go far to near; the GPU keeps that order.
            Integer[] idx = new Integer[candTranslucent];
            for (int i = 0; i < candTranslucent; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Float.compare(transDist[b], transDist[a]));
            int[] sorted = new int[candTranslucent];
            for (int i = 0; i < candTranslucent; i++) sorted[i] = transSlots[idx[i]];
            // Opaque near to far: the shader discards, so depth rejects only what nearer draws wrote first.
            java.util.Arrays.sort(opaqueOrder, 0, candOpaque);
            for (int i = 0; i < candOpaque; i++) opaqueSlots[i] = (int) opaqueOrder[i];
            renderer.setCandidates(opaqueSlots, candOpaque, sorted, candTranslucent);
            if (gpuSelect) renderer.traversedCandidates(nodes.size());
            org.lwjgl.vulkan.VkCommandBuffer cmd = net.vesu.vulkan.Drawer.currentCommandBuffer();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                net.vesu.vulkan.FrameTargets.suspendSceneForCompute(cmd, stack);
                if (gpuSelect) {
                    // The emptier the mesh queue, the more descent requests (AUDIT 5.11).
                    double room = Math.max(0, (4000 - meshes.queued()) / 4000.0);
                    int budget = (int) Math.ceil(LodTraversal.MAX_REQUESTS * room * room);
                    renderer.recordTraversal(cmd, rootCount, frame, (float) pixelScale, Initializer.CONFIG.lodQuality,
                            (float) lodBlocks, budget);
                }
                renderer.cull(cmd, net.vesu.vulkan.FrameTargets.scene.depthView(),
                        net.vesu.vulkan.FrameTargets.scene.width(), net.vesu.vulkan.FrameTargets.scene.height());
                net.vesu.vulkan.FrameTargets.resumeScene(cmd, stack);
            }
            net.vesu.vulkan.GpuTimer.mark(cmd, net.vesu.vulkan.GpuTimer.LOD_CULL);
            renderer.drawOpaqueGpu();
        } else {
            renderer.drawOpaque();
        }
        net.vesu.vulkan.GpuTimer.mark(net.vesu.vulkan.Drawer.currentCommandBuffer(), net.vesu.vulkan.GpuTimer.LOD_DRAW);
        int ao = renderer.ssao().samples();
        if (ao > 0 && (gpuSelect || drawnSections > 0)) renderer.recordSsao(viewProj, ao);
        net.vesu.vulkan.GpuTimer.mark(net.vesu.vulkan.Drawer.currentCommandBuffer(), net.vesu.vulkan.GpuTimer.LOD_SSAO);
        if ((frame & 255) == 0) cleanup();
    }

    void renderPackTranslucent() {
        if (!packOpen || renderer == null) return;
        packOpen = false;
        renderer.drawPackTranslucent();
    }

    void renderTranslucent() {
        if (!frameOpen || renderer == null) return;
        frameOpen = false;
        if (renderer.gpuCull) renderer.drawTranslucentGpu();
        else renderer.drawTranslucent();
    }

    private void applyResults() {
        long budget = 24L << 20;
        MeshResult r;
        while (budget > 0 && (r = results.peek()) != null) {
            Node n = nodes.get(r.key);
            if (n == null) {
                results.poll();
                continue;
            }
            long start = -1;
            if (r.quads.length > 0) {
                start = renderer.allocQuads(r.quads.length);
                if (start < 0) {
                    evictFor(r.quads.length);
                    start = renderer.allocQuads(r.quads.length);
                    if (start < 0) {
                        results.poll();
                        n.requestedAt = frame;   // retried later
                        continue;
                    }
                }
                if (!renderer.upload(r.quads, 0, r.quads.length, start)) {
                    renderer.freeQuads(start, r.quads.length);
                    break;   // staging full: next frame
                }
                budget -= r.quads.length * 8L;
            }
            results.poll();
            if (n.quadStart >= 0) renderer.freeQuads(n.quadStart, n.quadCount);
            n.quadStart = start;
            n.quadCount = r.quads.length;
            n.meshFrame = frame;
            if (n.lvl >= 1 && inRtRing(n)) {
                int t = r.counts[MeshResult.G_TRANSLUCENT];
                net.vesu.vulkan.rt.RtLod.offer(n.key, n.x << (5 + n.lvl), n.y << (5 + n.lvl), n.z << (5 + n.lvl), 1 << n.lvl,
                        r.quads, t, r.quads.length - t);
            } else {
                net.vesu.vulkan.rt.RtLod.release(n.key);
            }
            System.arraycopy(r.counts, 0, n.counts, 0, MeshResult.GROUPS);
            n.childMask = r.childMask;
            if (n.slot < 0 && r.quads.length > 0) {
                n.slot = renderer.allocSlot();
                if (n.slot >= 0) renderer.writeSection(n.slot, n.x << (5 + n.lvl), n.y << (5 + n.lvl), n.z << (5 + n.lvl), n.lvl);
            }
            if (n.slot >= 0) renderer.writeMeta(n.slot, Math.max(0, start), r.aabb, r.counts);
            writeGpu(n);
        }
    }

    private Node node(long key) {
        Node n = nodes.get(key);
        if (n == null) {
            n = new Node(key);
            nodes.put(key, n);
            n.requestedAt = frame;
            LodTraversal tr = traversal();
            if (tr != null) {
                n.gpuId = tr.allocNode();
                if (n.gpuId != 0) byId[n.gpuId] = n;
                writeGpu(n);
            }
            router.watch(key, LodWorld.BLOCK | LodWorld.CHILD_EXISTENCE);
        } else if (!n.ready() && frame - n.requestedAt > 600) {
            n.requestedAt = frame;
            submit(key);
        }
        return n;
    }

    private void dropNode(Node n) {
        LodTraversal tr = traversal();
        if (tr != null && n.gpuId != 0) {
            if (n.lvl < SectionKey.MAX_LEVEL) {
                Node parent = nodes.get(SectionKey.parent(n.key));
                if (parent != null) tr.setChild(parent.block, SectionKey.octantInParent(n.key), 0);
            }
            tr.freeBlock(n.block);
            tr.freeNode(n.gpuId);
            byId[n.gpuId] = null;
            n.gpuId = n.block = 0;
        }
        nodes.remove(n.key);
        net.vesu.vulkan.rt.RtLod.release(n.key);
        router.unwatch(n.key, LodWorld.BLOCK | LodWorld.CHILD_EXISTENCE);
        if (n.quadStart >= 0) renderer.freeQuads(n.quadStart, n.quadCount);
        if (n.slot >= 0) renderer.freeSlot(n.slot);
        n.quadStart = -1;
        n.slot = -1;
    }

    /** Frees the meshes drawn longest ago until {@code quads} fit (not those of the last frames). */
    private void evictFor(int quads) {
        syncStamps();
        evict.clear();
        nodes.forEachValue(n -> {
            if (n.quadStart >= 0 && n.lastSeen < frame - 2) evict.add(n);
        });
        evict.sort((a, b) -> Integer.compare(a.lastSeen, b.lastSeen));
        long freed = 0;
        for (Node n : evict) {
            if (freed >= quads * 2L) break;
            freed += n.quadCount;
            dropNode(n);
        }
    }

    private void cleanup() {
        syncStamps();
        evict.clear();
        nodes.forEachValue(n -> {
            if (n.lastSeen < frame - 1200) evict.add(n);
        });
        for (Node n : evict) dropNode(n);
    }

    private double pixelScale;
    private double lodBlocks;

    private void select(Minecraft mc) {
        drawnSections = 0;
        visitedNodes = 0;
        if (!Initializer.CONFIG.lodRendering) return;
        int h = Math.max(1, net.vesu.vulkan.FrameTargets.scene.height());
        pixelScale = h / (2.0 * Math.tan(CameraHistory.fovY() * 0.5));
        lodBlocks = Initializer.CONFIG.lodDistance * 16.0;
        int r = (int) Math.ceil(lodBlocks / 512.0);
        int cx = (int) Math.floor(camX / 512.0), cz = (int) Math.floor(camZ / 512.0);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                visit(SectionKey.of(4, cx + dx, 0, cz + dz));
            }
        }
    }

    private boolean inRange(double x0, double z0, double size) {
        double nx = Math.max(x0, Math.min(camX, x0 + size)) - camX;
        double nz = Math.max(z0, Math.min(camZ, z0 + size)) - camZ;
        return nx * nx + nz * nz <= lodBlocks * lodBlocks;
    }

    private boolean inFrustum(double x0, double y0, double z0, double size) {
        return inPlanes(planes, x0, y0, z0, size);
    }

    private boolean inPlanes(Vector4f[] planes, double x0, double y0, double z0, double size) {
        float ax = (float) (x0 - camX), ay = (float) (y0 - camY), az = (float) (z0 - camZ);
        float bx = ax + (float) size, by = ay + (float) size, bz = az + (float) size;
        for (Vector4f p : planes) {
            float x = p.x >= 0 ? bx : ax, y = p.y >= 0 ? by : ay, z = p.z >= 0 ? bz : az;
            if (p.x * x + p.y * y + p.z * z + p.w < 0) return false;
        }
        return true;
    }

    private void visit(long key) {
        int lvl = SectionKey.lvl(key);
        double size = 32 << lvl;
        double x0 = SectionKey.x(key) * size, y0 = SectionKey.y(key) * size, z0 = SectionKey.z(key) * size;
        if (y0 >= 256 || y0 + size <= 0) return;
        if (!inRange(x0, z0, size)) return;
        if (!inFrustum(x0, y0, z0, size)) return;
        visitedNodes++;
        Node n = node(key);
        n.lastSeen = frame;
        if (!n.ready()) return;
        // Under a pack with DH programs vanilla terrain draws over the LODs, and packs fade it
        // out near its edge for DH to fill: what vanilla has built stays.
        if (lvl <= 1 && (!packMode || packTerrain) && coveredBox(x0, y0, z0, size) && insideEdge(x0, z0, size)) return;
        if (lvl > 0 && n.childMask != 0) {
            double dx = Math.max(x0 - camX, Math.max(0, camX - (x0 + size)));
            double dy = Math.max(y0 - camY, Math.max(0, camY - (y0 + size)));
            double dz = Math.max(z0 - camZ, Math.max(0, camZ - (z0 + size)));
            double d = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
            double px = size * pixelScale / d;
            if (px > Initializer.CONFIG.lodQuality) {
                // Refine once every child that exists has its mesh; meanwhile draw this one.
                boolean all = true;
                for (int o = 0; o < 8; o++) {
                    if ((n.childMask & 1 << o) == 0) continue;
                    long ck = SectionKey.child(key, o);
                    double cs = size / 2;
                    double cx0 = SectionKey.x(ck) * cs, cy0 = SectionKey.y(ck) * cs, cz0 = SectionKey.z(ck) * cs;
                    if (!inRange(cx0, cz0, cs)) continue;
                    Node c = node(ck);
                    c.lastSeen = frame;
                    if (!c.ready()) all = false;
                }
                if (all) {
                    for (int o = 0; o < 8; o++) if ((n.childMask & 1 << o) != 0) visit(SectionKey.child(key, o));
                    return;
                }
            }
        }
        draw(n, x0, y0, z0, size);
    }

    // ---- The pack's shadow map ----

    private final Matrix4f shadowMvp = new Matrix4f(), shadowInv = new Matrix4f();
    private final Vector4f[] shadowPlanes = new Vector4f[6];
    private double vanillaBlocks;

    /**
     * Levels 3 and 4 into the pack's shadow map past the vanilla distance, for long-range sun
     * shadows (AUDIT 5.16, the lodPackShadows option). Positions are camera-relative, so the
     * light's view applies as it does to vanilla terrain.
     */
    private void renderShadow(Minecraft mc) {
        if (!Initializer.CONFIG.lodPackShadows || net.vesu.vulkan.LodPackBridge.shadowProgram() == null
                || net.vesu.vulkan.LodPackBridge.shadowCommands() == null) return;
        Matrix4f view = net.vesu.shader.FrameUniforms.shadowView();
        net.vesu.shader.FrameUniforms.shadowProjectionRemapped().mul(view, shadowMvp);
        for (int i = 0; i < 6; i++) {
            if (shadowPlanes[i] == null) shadowPlanes[i] = new Vector4f();
            shadowMvp.frustumPlane(i, shadowPlanes[i]);
        }
        vanillaBlocks = mc.gameSettings.renderDistanceChunks * 16.0;
        int r = (int) Math.ceil(lodBlocks / 512.0);
        int cx = (int) Math.floor(camX / 512.0), cz = (int) Math.floor(camZ / 512.0);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                shadowVisit(SectionKey.of(4, cx + dx, 0, cz + dz));
            }
        }
        int bx = (int) Math.floor(camX), by = (int) Math.floor(camY), bz = (int) Math.floor(camZ);
        renderer.drawPackShadow(shadowMvp, view, bx, by, bz, (float) (camX - bx), (float) (camY - by), (float) (camZ - bz),
                shadowInv.set(shadowMvp).invert(), net.vesu.vulkan.LodPackBridge.shadowResolution(), (int) vanillaBlocks);
    }

    private void shadowVisit(long key) {
        int lvl = SectionKey.lvl(key);
        double size = 32 << lvl;
        double x0 = SectionKey.x(key) * size, y0 = SectionKey.y(key) * size, z0 = SectionKey.z(key) * size;
        if (y0 >= 256 || y0 + size <= 0) return;
        if (!inRange(x0, z0, size)) return;
        // Wholly inside the vanilla distance: vanilla terrain is in the map there.
        double fx = Math.max(Math.abs(x0 - camX), Math.abs(x0 + size - camX));
        double fz = Math.max(Math.abs(z0 - camZ), Math.abs(z0 + size - camZ));
        if (fx * fx + fz * fz < vanillaBlocks * vanillaBlocks) return;
        if (!inPlanes(shadowPlanes, x0, y0, z0, size)) return;
        Node n = nodes.get(key);
        if (n == null || !n.ready()) return;
        if (lvl == 4 && n.childMask != 0) {
            boolean all = true;
            for (int o = 0; o < 8 && all; o++) {
                if ((n.childMask & 1 << o) == 0) continue;
                Node c = nodes.get(SectionKey.child(key, o));
                all = c != null && c.ready() && c.meshFrame != frame;
            }
            if (all) {
                for (int o = 0; o < 8; o++) if ((n.childMask & 1 << o) != 0) shadowVisit(SectionKey.child(key, o));
                return;
            }
        }
        if (n.quadStart < 0 || n.slot < 0 || n.meshFrame == frame) return;
        long q = n.quadStart;
        for (int g = 0; g < MeshResult.GROUPS; g++) {
            int c = n.counts[g];
            if (c > 0 && g != MeshResult.G_TRANSLUCENT) renderer.addShadow(q, c, n.slot);
            q += c;
        }
    }

    // ---- GPU traversal (AUDIT 5.11) ----

    private final Node[] byId = new Node[LodTraversal.MAX_NODES];
    private final int[] requestIds = new int[LodTraversal.MAX_REQUESTS];
    private int rootCount, stampsSynced = -1;

    /** The renderer's traversal, or null where the CPU selects (no indirect count, VESU_LOD_CPU_SELECT). */
    private LodTraversal traversal() {
        if (renderer == null || !renderer.gpuCull || net.vesu.Env.get("VESU_LOD_CPU_SELECT") != null) return null;
        return renderer.traversal();
    }

    private void writeGpu(Node n) {
        LodTraversal tr = traversal();
        if (tr == null || n.gpuId == 0) return;
        int slot = n.quadStart >= 0 && n.slot >= 0 ? n.slot : LodTraversal.NO_SLOT;
        tr.writeNode(n.gpuId, n.block, slot, Math.max(0, n.childMask), n.ready(), n.counts[MeshResult.G_TRANSLUCENT] > 0,
                n.lvl, n.x, n.y, n.z);
    }

    /**
     * A request of the traversal: a node without its mesh asks again (when the last request
     * is old), a node that wants to descend gets its children, created as needed.
     */
    private void answer(Node n) {
        if (!n.ready()) {
            node(n.key);
            return;
        }
        if (n.childMask <= 0) return;
        LodTraversal tr = traversal();
        if (n.block == 0) {
            n.block = tr.allocBlock();
            if (n.block == 0) return;
        }
        for (int o = 0; o < 8; o++) {
            if ((n.childMask & 1 << o) == 0) continue;
            Node c = node(SectionKey.child(n.key, o));
            c.lastSeen = Math.max(c.lastSeen, frame);
            tr.setChild(n.block, o, c.gpuId);
        }
        writeGpu(n);
    }

    /** The top-level nodes in range, for the traversal to start from. */
    private void selectRoots(Minecraft mc) {
        rootCount = 0;
        drawnSections = 0;
        visitedNodes = 0;
        if (!Initializer.CONFIG.lodRendering) return;
        int h = Math.max(1, net.vesu.vulkan.FrameTargets.scene.height());
        pixelScale = h / (2.0 * Math.tan(CameraHistory.fovY() * 0.5));
        lodBlocks = Initializer.CONFIG.lodDistance * 16.0;
        LodTraversal tr = traversal();
        int r = (int) Math.ceil(lodBlocks / 512.0);
        int cx = (int) Math.floor(camX / 512.0), cz = (int) Math.floor(camZ / 512.0);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                long key = SectionKey.of(4, cx + dx, 0, cz + dz);
                if (!inRange((cx + dx) * 512.0, (cz + dz) * 512.0, 512)) continue;
                Node n = node(key);
                n.lastSeen = frame;
                if (n.gpuId != 0 && rootCount < tr.maxRoots()) tr.setRoot(renderer.frameSlot(), rootCount++, n.gpuId);
            }
        }
    }

    /** The visibility stamps of the traversal become the nodes' last-seen frames. */
    private void syncStamps() {
        LodTraversal tr = traversal();
        if (tr == null || stampsSynced == frame) return;
        stampsSynced = frame;
        nodes.forEachValue(n -> {
            int v = tr.visited(n.gpuId);
            if (v > n.lastSeen) n.lastSeen = v;
        });
    }

    /** The RT ring from the nodes the traversal drew lately (it has no per-node draw on the CPU). */
    private void rtRingFromStamps() {
        LodTraversal tr = traversal();
        nodes.forEachValue(n -> {
            if (n.lvl < 1 || n.quadStart < 0 || tr.drawn(n.gpuId) < frame - 35 || !inRtRing(n)) return;
            if (!net.vesu.vulkan.rt.RtLod.touch(n.key) && frame - n.rtAsked > 600) {
                n.rtAsked = frame;
                submit(n.key);
            }
        });
    }

    // ---- The RT scene (AUDIT 5.17) ----

    /** This frame's selection feeds the RT scene. */
    private boolean rtRing;

    /** Within lodRtRing top-level sections past the vanilla distance (nearer counts too). */
    private boolean inRtRing(Node n) {
        if (!net.vesu.vulkan.rt.RtScene.enabled() || Initializer.CONFIG.lodRtRing <= 0) return false;
        double size = 32 << n.lvl, x0 = n.x * size, z0 = n.z * size;
        double nx = Math.max(x0, Math.min(camX, x0 + size)) - camX;
        double nz = Math.max(z0, Math.min(camZ, z0 + size)) - camZ;
        double ring = Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16.0 + Initializer.CONFIG.lodRtRing * 512.0;
        return nx * nx + nz * nz <= ring * ring;
    }

    private int[] opaqueSlots = new int[4096], transSlots = new int[1024];
    private long[] opaqueOrder = new long[4096];
    private float[] transDist = new float[1024];
    private int candOpaque, candTranslucent;

    private void draw(Node n, double x0, double y0, double z0, double size) {
        if (n.quadStart < 0 || n.slot < 0) return;
        drawnSections++;
        if (rtRing && n.lvl >= 1 && inRtRing(n) && !net.vesu.vulkan.rt.RtLod.touch(n.key) && frame - n.rtAsked > 600) {
            // Meshed before it entered the ring: its quads are needed again for the BLAS.
            n.rtAsked = frame;
            submit(n.key);
        }
        if (renderer.gpuCull && !packMode) {
            if (candOpaque == opaqueSlots.length) {
                opaqueSlots = java.util.Arrays.copyOf(opaqueSlots, candOpaque * 2);
                opaqueOrder = java.util.Arrays.copyOf(opaqueOrder, candOpaque * 2);
            }
            double ox = x0 + size / 2 - camX, oy = y0 + size / 2 - camY, oz = z0 + size / 2 - camZ;
            // Near to far by the squared distance of the center (positive float bits sort as ints).
            opaqueOrder[candOpaque++] = (long) Float.floatToIntBits((float) (ox * ox + oy * oy + oz * oz)) << 32 | (n.slot & 0xFFFFFFFFL);
            if (n.counts[MeshResult.G_TRANSLUCENT] > 0) {
                if (candTranslucent == transSlots.length) {
                    transSlots = java.util.Arrays.copyOf(transSlots, candTranslucent * 2);
                    transDist = java.util.Arrays.copyOf(transDist, candTranslucent * 2);
                }
                double cxr = x0 + size / 2 - camX, cyr = y0 + size / 2 - camY, czr = z0 + size / 2 - camZ;
                transDist[candTranslucent] = (float) (cxr * cxr + cyr * cyr + czr * czr);
                transSlots[candTranslucent++] = n.slot;
            }
            return;
        }
        long q = n.quadStart;
        double cxr = x0 + size / 2 - camX, cyr = y0 + size / 2 - camY, czr = z0 + size / 2 - camZ;
        float dist = (float) Math.sqrt(cxr * cxr + cyr * cyr + czr * czr);
        for (int g = 0; g < MeshResult.GROUPS; g++) {
            int c = n.counts[g];
            if (c == 0) continue;
            if (g == MeshResult.G_TRANSLUCENT) {
                renderer.addTranslucent(q, c, n.slot, dist);
            } else if (g == MeshResult.G_DOUBLE || faceVisible(g - MeshResult.G_FACE, x0, y0, z0, size)) {
                renderer.addOpaque(q, c, n.slot);
            }
            q += c;
        }
    }

    /** A face group can be seen only from its own side of the section. */
    private boolean faceVisible(int face, double x0, double y0, double z0, double size) {
        return switch (face) {
            case 0 -> camY < y0 + size;
            case 1 -> camY > y0;
            case 2 -> camZ < z0 + size;
            case 3 -> camZ > z0;
            case 4 -> camX < x0 + size;
            default -> camX > x0;
        };
    }

    // ---- Coverage of vanilla terrain ----

    private void buildCover(Minecraft mc) {
        int rd = mc.gameSettings.renderDistanceChunks + 1;
        coverN = Math.min(69, rd * 2 + 1);
        int half = coverN / 2;
        coverX = ((int) Math.floor(camX) >> 4) - half;
        coverZ = ((int) Math.floor(camZ) >> 4) - half;
        int words = (coverN * coverN * 16 + 31) / 32;
        java.util.Arrays.fill(cover, 0, words, 0);
        // What vanilla draws this frame, not what it has built: its visibility flood does not
        // pass through sections still waiting for a build, so a built section behind them is
        // left undrawn and must not hide the LOD there.
        java.util.List<?> infos = mc.renderGlobal == null ? null
                : ((net.vesu.mixin.render.RenderGlobalAccessor) mc.renderGlobal).vesu$getRenderInfos();
        if (infos == null) return;
        for (int i = 0, n = infos.size(); i < n; i++) {
            RenderChunk rc = ((net.vesu.mixin.render.ContainerInfoAccessor) infos.get(i)).vesu$getRenderChunk();
            if (rc == null || rc.getCompiledChunk() == CompiledChunk.DUMMY) continue;
            BlockPos p = rc.getPosition();
            int gx = (p.getX() >> 4) - coverX, gz = (p.getZ() >> 4) - coverZ, gy = p.getY() >> 4;
            if (gx < 0 || gz < 0 || gx >= coverN || gz >= coverN || gy < 0 || gy > 15) continue;
            int bit = (gz * coverN + gx) * 16 + gy;
            cover[bit >> 5] |= 1 << (bit & 31);
        }
    }

    private boolean coveredSection(int sx, int sy, int sz) {
        int gx = sx - coverX, gz = sz - coverZ;
        if (sy < 0 || sy > 15) return true;
        if (gx < 0 || gz < 0 || gx >= coverN || gz >= coverN) return false;
        int bit = (gz * coverN + gx) * 16 + sy;
        return (cover[bit >> 5] & 1 << (bit & 31)) != 0;
    }

    /** Every chunk section in the box is built by vanilla, so the node would be discarded anyway. */
    private boolean coveredBox(double x0, double y0, double z0, double size) {
        int sx0 = (int) Math.floor(x0) >> 4, sy0 = (int) Math.floor(y0) >> 4, sz0 = (int) Math.floor(z0) >> 4;
        int n = (int) size >> 4;
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) for (int c = 0; c < n; c++) {
            if (!coveredSection(sx0 + a, sy0 + b, sz0 + c)) return false;
        }
        return true;
    }

    /** The box lies inside the vanilla edge band, where vanilla hides the LODs completely. */
    private boolean insideEdge(double x0, double z0, double size) {
        float start = net.vesu.vulkan.VRenderSystem.lodEdgeStart, end = net.vesu.vulkan.VRenderSystem.lodEdgeEnd;
        if (end <= start) return true;
        double dx = Math.max(Math.abs(x0 - camX), Math.abs(x0 + size - camX));
        double dz = Math.max(Math.abs(z0 - camZ), Math.abs(z0 + size - camZ));
        return dx * dx + dz * dz < (double) start * start;
    }

    /** The LOD uniforms of this frame; {@code dropCovered}: the traversal skips what vanilla has built. */
    private void writeUniforms(Minecraft mc, boolean dropCovered) {
        ByteBuffer b = ubo.clear();
        viewProj.get(0, b);
        int bx = (int) Math.floor(camX), by = (int) Math.floor(camY), bz = (int) Math.floor(camZ);
        b.putInt(64, bx).putInt(68, by).putInt(72, bz).putInt(76, 0);
        b.putFloat(80, (float) (camX - bx)).putFloat(84, (float) (camY - by)).putFloat(88, (float) (camZ - bz)).putFloat(92, 0);
        float[] fc = VRenderSystem.getShaderFogColor();
        b.putFloat(96, fc[0]).putFloat(100, fc[1]).putFloat(104, fc[2]).putFloat(108, 1);
        String fogMode = Initializer.CONFIG.lodFog;
        boolean fog = !"off".equals(fogMode) && !"fade".equals(fogMode);
        boolean fade = !"off".equals(fogMode) && !"fog".equals(fogMode);
        b.putFloat(112, VRenderSystem.getShaderFogStart()).putFloat(116, VRenderSystem.getShaderFogEnd())
                .putFloat(120, fog && VRenderSystem.isFogEnabled() ? VRenderSystem.getFogState() : 0)
                .putFloat(124, VRenderSystem.getFogDensity());
        b.putFloat(128, fade ? (float) (lodBlocks * 0.9) : 0).putFloat(132, fade ? (float) lodBlocks : 0)
                .putFloat(136, renderer.atlasWidth()).putFloat(140, renderer.atlasHeight());
        b.putInt(144, coverX).putInt(148, coverN).putInt(152, coverZ).putInt(156, dropCovered ? 1 : 0);
        b.putFloat(160, net.vesu.vulkan.VRenderSystem.lodEdgeStart).putFloat(164, net.vesu.vulkan.VRenderSystem.lodEdgeEnd)
                .putFloat(168, 0).putFloat(172, 0);
        b.limit(176);
        renderer.setFrame(b, cover, (coverN * coverN * 16 + 31) / 32);
    }

    // ---- Ingest ----

    void ingest(RawChunk c) {
        if (Initializer.CONFIG.lodIngest) ingest.add(c);
    }

    void reload() {
        nodes.forEachValue(n -> {
            if (n.ready()) submit(n.key);
        });
    }

    List<String> debugLines() {
        List<String> l = new ArrayList<>();
        l.add(String.format("LOD nodes %d, visited %d, drawn %d (%d+%d draws)", nodes.size(), visitedNodes, drawnSections,
                renderer == null ? 0 : renderer.opaqueDraws(), renderer == null ? 0 : renderer.translucentDraws()));
        l.add(String.format("LOD cache %d/%d", world.primaryCached(), world.secondaryCached()));
        l.add(String.format("LOD mesh queue %d, failed %d", meshes.queued(), meshes.failedBuilds()));
        l.add(String.format("LOD models %d, baking %d", bakery.modelCount(), bakery.pending()));
        if (renderer != null) {
            l.add(String.format("LOD arena %d/%d MB", renderer.arenaUsedBytes() >> 20, renderer.arenaCapacityBytes() >> 20));
        }
        if (renderer != null) l.add("LOD SSAO " + renderer.ssao().modeLabel());
        l.add(String.format("LOD ingest %d, save %d", ingest.queued(), world.saveService().queued()));
        if (importer != null) l.add(importer.progress());
        if (generator != null && Initializer.CONFIG.lodGenerationDistance > 0) l.add(generator.progress());
        return l;
    }

    boolean hasSky() {
        return sky;
    }
}
