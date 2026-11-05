package net.vesu.render;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.vesu.Initializer;
import net.vesu.vulkan.ShadowPass;
import net.vesu.vulkan.rt.RtScene;

import java.util.concurrent.locks.LockSupport;

/**
 * Occlusion culling of entities and block entities (setting {@code entityCulling}), after
 * Entity Culling (tr7zw). Vanilla only drops what is outside the frustum or in a chunk section
 * its visibility graph cannot reach; a mob behind the wall of a reachable section is still
 * tessellated and drawn.
 *
 * <p>Once per client tick the client thread snapshots the candidates (render box, a margin for
 * their motion); a daemon thread traces rays from the camera to points on the faces of each box
 * that look at it, through full opaque cubes ({@code isOpaqueCube}), and stores the verdict on
 * the object ({@link Cullable}). An object seen stays drawn for {@link #GRACE_NANOS} more, new
 * objects start drawn, and anything not traced (too far, too big, glowing, named, other
 * players, global block entity renderers) is never culled: a late verdict costs a frame drawn,
 * never a missing object.</p>
 *
 * <p>Off with ray tracing (hidden entities still cast shadows and show in reflections). Under a
 * shader pack shadow map, culled entities are drawn into the map by {@code ShadowPass} like the
 * ones outside the frustum; block entities are not culled then.</p>
 *
 * <p>The same setting caps the copies drawn for far dropped item stacks ({@link #itemCopies}).</p>
 */
public final class OcclusionCuller {

    /** State merged into {@code Entity} and {@code TileEntity} by {@code CullableM}. */
    public interface Cullable {
        boolean vesu$isCulled();
        void vesu$setCulled(boolean culled);
        long vesu$visibleUntil();
        void vesu$setVisibleUntil(long nanos);
    }

    private static final double ENTITY_DISTANCE = 128.0, TILE_DISTANCE = 64.0, SIZE_LIMIT = 50.0;
    private static final long GRACE_NANOS = 1_000_000_000L;
    private static final long WAIT_NANOS = 10_000_000L, IDLE_NANOS = 100_000_000L;
    /** Rays per box face: a 3x3 grid. */
    private static final int GRID = 3;
    /** Points sit this far inside the box, off the voxel boundaries a box usually lies on. */
    private static final double INSET = 0.01;

    /** Candidates of one tick, immutable once published. */
    private record Snapshot(WorldClient world, Cullable[] objects, double[] boxes, int count, int entities) {}

    private static volatile boolean active;
    private static volatile Snapshot snapshot;
    /** Camera position of the latest frame, {x, y, z}. */
    private static volatile double[] camera;
    private static Thread thread;

    private static volatile int seenEntities, culledEntities, seenTiles, culledTiles;
    private static volatile long passNanos;

    private OcclusionCuller() {}

    public static void init() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(OcclusionCuller.class);
    }

    /** True when {@code entity} (an Entity or TileEntity) should not be drawn this frame. */
    public static boolean culled(Object object) {
        if (!active || !((Cullable) object).vesu$isCulled()) return false;
        // Block entities draw into a pack shadow map only from the main pass.
        return !(object instanceof TileEntity) || !ShadowPass.active();
    }

    /** Copies of a dropped item stack worth drawing at a squared camera distance (at most what vanilla asks). */
    public static int itemCopies(int copies, double distanceSq) {
        if (!Initializer.CONFIG.entityCulling || !Initializer.isVulkanActive() || copies <= 1) return copies;
        // All five copies within ~8.5 blocks, one past ~19 (after Flerovium).
        int cap = distanceSq < 1.0 ? copies : (int) Math.ceil(360.0 / distanceSq);
        return Math.max(1, Math.min(copies, cap));
    }

    /** Head of {@code RenderGlobal.renderEntities}: the camera the rays start from. */
    public static void setCamera(Entity viewer, float partialTicks) {
        if (!active || viewer == null) return;
        Vec3d eye = ActiveRenderInfo.projectViewFromEntity(viewer, partialTicks);
        double px = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partialTicks;
        double py = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partialTicks + viewer.getEyeHeight();
        double pz = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partialTicks;
        // The third-person camera sits a few blocks back; anything farther is a bad matrix read.
        if (eye.squareDistanceTo(px, py, pz) > 100.0) eye = new Vec3d(px, py, pz);
        double[] c = camera;
        if (c == null || c[0] != eye.x || c[1] != eye.y || c[2] != eye.z) camera = new double[]{eye.x, eye.y, eye.z};
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        LeafCulling.update();
        Minecraft mc = Minecraft.getMinecraft();
        boolean on = Initializer.CONFIG.entityCulling && Initializer.isVulkanActive() && !RtScene.enabled()
                && mc.world != null && mc.getRenderViewEntity() != null;
        if (!on) {
            active = false;
            snapshot = null;
            return;
        }
        if (thread == null) {
            thread = new Thread(OcclusionCuller::run, "Vesu occlusion culling");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }
        snapshot = capture(mc);
        active = true;
        LockSupport.unpark(thread);
    }

    @SubscribeEvent
    public static void onDebugText(RenderGameOverlayEvent.Text e) {
        if (!Minecraft.getMinecraft().gameSettings.showDebugInfo || !active) return;
        e.getRight().add("");
        e.getRight().add(String.format("Occlusion culled: E %d/%d, BE %d/%d, %.2f ms",
                culledEntities, seenEntities, culledTiles, seenTiles, passNanos / 1.0e6));
    }

    // ---- Client thread ----

    private static Snapshot capture(Minecraft mc) {
        WorldClient world = mc.world;
        Entity viewer = mc.getRenderViewEntity();
        double vx = viewer.posX, vy = viewer.posY, vz = viewer.posZ;
        int capacity = world.loadedEntityList.size() + world.loadedTileEntityList.size();
        Cullable[] objects = new Cullable[capacity];
        double[] boxes = new double[capacity * 6];
        int n = 0;

        for (Entity entity : world.loadedEntityList) {
            if (n == capacity) break;
            Cullable c = (Cullable) entity;
            if (entity == viewer || entity instanceof EntityPlayer || entity.ignoreFrustumCheck || entity.isGlowing()
                    || (entity.getAlwaysRenderNameTagForRender() && entity.hasCustomName())
                    || entity.isRidingOrBeingRiddenBy(viewer)) {
                c.vesu$setCulled(false);
                continue;
            }
            AxisAlignedBB box = entity.getRenderBoundingBox();
            // The verdict lags a tick or two behind: grow the box by the way the entity moves.
            double margin = 0.5 + 2.0 * Math.max(Math.abs(entity.motionX), Math.max(Math.abs(entity.motionY), Math.abs(entity.motionZ)));
            if (!traceable(box, vx, vy, vz, ENTITY_DISTANCE)) {
                c.vesu$setCulled(false);
                continue;
            }
            objects[n] = c;
            put(boxes, n++, box, margin);
        }
        int entities = n;

        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        for (TileEntity tile : world.loadedTileEntityList) {
            if (n == capacity) break;
            TileEntitySpecialRenderer<TileEntity> renderer = dispatcher.getRenderer(tile);
            if (renderer == null) continue;
            Cullable c = (Cullable) tile;
            AxisAlignedBB box = tile.getRenderBoundingBox();
            if (renderer.isGlobalRenderer(tile) || box == TileEntity.INFINITE_EXTENT_AABB
                    || !traceable(box, vx, vy, vz, TILE_DISTANCE)) {
                c.vesu$setCulled(false);
                continue;
            }
            objects[n] = c;
            put(boxes, n++, box, 0.0);
        }
        return new Snapshot(world, objects, boxes, n, entities);
    }

    /** Small enough and near enough to trace. */
    private static boolean traceable(AxisAlignedBB box, double x, double y, double z, double distance) {
        if (box.maxX - box.minX > SIZE_LIMIT || box.maxY - box.minY > SIZE_LIMIT || box.maxZ - box.minZ > SIZE_LIMIT) return false;
        double dx = Math.max(0.0, Math.max(box.minX - x, x - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - y, y - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - z, z - box.maxZ));
        return dx * dx + dy * dy + dz * dz < distance * distance;
    }

    private static void put(double[] boxes, int i, AxisAlignedBB box, double margin) {
        boxes[i * 6] = box.minX - margin;
        boxes[i * 6 + 1] = box.minY - margin;
        boxes[i * 6 + 2] = box.minZ - margin;
        boxes[i * 6 + 3] = box.maxX + margin;
        boxes[i * 6 + 4] = box.maxY + margin;
        boxes[i * 6 + 5] = box.maxZ + margin;
    }

    // ---- Culling thread ----

    private static void run() {
        Tracer tracer = new Tracer();
        Snapshot lastSnapshot = null;
        double[] lastCamera = null;
        boolean failed = false;
        while (true) {
            LockSupport.parkNanos(active ? WAIT_NANOS : IDLE_NANOS);
            Snapshot s = snapshot;
            double[] cam = camera;
            if (!active || s == null || cam == null || (s == lastSnapshot && cam == lastCamera)) continue;
            lastSnapshot = s;
            lastCamera = cam;
            long start = System.nanoTime();
            try {
                pass(tracer, s, cam, start);
            } catch (Throwable t) {
                // The world changes under the thread (chunks load and unload): drop the pass.
                if (!failed) Initializer.LOGGER.debug("[culling] pass failed", t);
                failed = true;
            }
            passNanos = System.nanoTime() - start;
        }
    }

    private static void pass(Tracer tracer, Snapshot s, double[] cam, long now) {
        tracer.reset(s.world());
        double cx = cam[0], cy = cam[1], cz = cam[2];
        // A camera inside an opaque block (spectator, suffocating) would cull everything.
        boolean blind = tracer.opaque(MathHelper.floor(cx), MathHelper.floor(cy), MathHelper.floor(cz));
        int entities = 0, tiles = 0;
        double[] b = s.boxes();
        for (int i = 0; i < s.count(); i++) {
            Cullable c = s.objects()[i];
            boolean visible = blind || tracer.boxVisible(cx, cy, cz,
                    b[i * 6], b[i * 6 + 1], b[i * 6 + 2], b[i * 6 + 3], b[i * 6 + 4], b[i * 6 + 5]);
            if (visible) {
                c.vesu$setVisibleUntil(now + GRACE_NANOS);
                c.vesu$setCulled(false);
            } else if (now - c.vesu$visibleUntil() > 0) {
                c.vesu$setCulled(true);
            }
            if (c.vesu$isCulled()) {
                if (i < s.entities()) entities++;
                else tiles++;
            }
        }
        seenEntities = s.entities();
        seenTiles = s.count() - s.entities();
        culledEntities = entities;
        culledTiles = tiles;
    }

    /** Voxel rays through the world's opaque cubes, with the block lookups of one pass cached. */
    private static final class Tracer {
        private static final byte OPAQUE = 1, CLEAR = 2;
        private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap(1 << 14);
        private WorldClient world;
        private Chunk chunk;

        void reset(WorldClient world) {
            if (cache.size() > 1 << 18) cache.trim(1 << 14);
            cache.clear();
            this.world = world;
            this.chunk = null;
        }

        boolean opaque(int x, int y, int z) {
            if (y < 0 || y > 255) return false;
            long key = ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
            byte v = cache.get(key);
            if (v == 0) {
                int cx = x >> 4, cz = z >> 4;
                Chunk c = chunk;
                if (c == null || c.x != cx || c.z != cz) chunk = c = world.getChunk(cx, cz);
                IBlockState state = c.getBlockState(x, y, z);
                v = state.isOpaqueCube() ? OPAQUE : CLEAR;
                cache.put(key, v);
            }
            return v == OPAQUE;
        }

        /** True when a ray from the camera reaches a point on a face of the box that looks at it. */
        boolean boxVisible(double cx, double cy, double cz, double x0, double y0, double z0, double x1, double y1, double z1) {
            if (cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1 && cz >= z0 && cz <= z1) return true;
            x0 += INSET; y0 += INSET; z0 += INSET;
            x1 -= INSET; y1 -= INSET; z1 -= INSET;
            // Faces toward the camera: the one at the near side of every slab the camera is outside of.
            if (cx < x0 && face(cx, cy, cz, 0, x0, y0, y1, z0, z1)) return true;
            if (cx > x1 && face(cx, cy, cz, 0, x1, y0, y1, z0, z1)) return true;
            if (cy < y0 && face(cx, cy, cz, 1, y0, x0, x1, z0, z1)) return true;
            if (cy > y1 && face(cx, cy, cz, 1, y1, x0, x1, z0, z1)) return true;
            if (cz < z0 && face(cx, cy, cz, 2, z0, x0, x1, y0, y1)) return true;
            return cz > z1 && face(cx, cy, cz, 2, z1, x0, x1, y0, y1);
        }

        /** A GRID x GRID set of points on the face at {@code at} along {@code axis}, spanned by (u, v). */
        private boolean face(double cx, double cy, double cz, int axis, double at, double u0, double u1, double v0, double v1) {
            for (int i = 0; i < GRID; i++) {
                double u = u0 + (u1 - u0) * i / (GRID - 1);
                for (int j = 0; j < GRID; j++) {
                    double v = v0 + (v1 - v0) * j / (GRID - 1);
                    boolean hit = switch (axis) {
                        case 0 -> ray(cx, cy, cz, at, u, v);
                        case 1 -> ray(cx, cy, cz, u, at, v);
                        default -> ray(cx, cy, cz, u, v, at);
                    };
                    if (hit) return true;
                }
            }
            return false;
        }

        /** Amanatides-Woo walk from the camera voxel to the target voxel; false at the first opaque one between. */
        private boolean ray(double ax, double ay, double az, double bx, double by, double bz) {
            int x = MathHelper.floor(ax), y = MathHelper.floor(ay), z = MathHelper.floor(az);
            int tx = MathHelper.floor(bx), ty = MathHelper.floor(by), tz = MathHelper.floor(bz);
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
            double adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
            double deltaX = adx > 1e-9 ? 1.0 / adx : Double.POSITIVE_INFINITY;
            double deltaY = ady > 1e-9 ? 1.0 / ady : Double.POSITIVE_INFINITY;
            double deltaZ = adz > 1e-9 ? 1.0 / adz : Double.POSITIVE_INFINITY;
            double maxX = adx > 1e-9 ? (sx > 0 ? x + 1 - ax : ax - x) * deltaX : Double.POSITIVE_INFINITY;
            double maxY = ady > 1e-9 ? (sy > 0 ? y + 1 - ay : ay - y) * deltaY : Double.POSITIVE_INFINITY;
            double maxZ = adz > 1e-9 ? (sz > 0 ? z + 1 - az : az - z) * deltaZ : Double.POSITIVE_INFINITY;
            int steps = Math.abs(tx - x) + Math.abs(ty - y) + Math.abs(tz - z);
            for (int i = 0; i < steps; i++) {
                if (maxX < maxY && maxX < maxZ) {
                    x += sx;
                    maxX += deltaX;
                } else if (maxY < maxZ) {
                    y += sy;
                    maxY += deltaY;
                } else {
                    z += sz;
                    maxZ += deltaZ;
                }
                if (x == tx && y == ty && z == tz) return true;
                if (opaque(x, y, z)) return false;
            }
            return true;
        }
    }
}
