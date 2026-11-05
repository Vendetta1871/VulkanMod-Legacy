package net.vesu.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.vesu.Initializer;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Particle work vanilla 1.12.2 does for nothing (after Particle Core and Flerovium):
 *
 * <ul>
 *   <li>every particle is tessellated, also behind the camera: those outside the frustum are
 *       skipped ({@code particleCulling});</li>
 *   <li>breaking a block spawns 64 particles wherever it is: none for a block outside the view
 *       ({@code particleCulling});</li>
 *   <li>every particle looks its light up in the world every frame: one lookup per block and
 *       tick, shared by all particles in it;</li>
 *   <li>per-type spawn chances ({@code particleSpawnChance}).</li>
 * </ul>
 *
 * Everything here runs on the client thread; the camera is the frustum vanilla built for the
 * frame, taken at the head of {@code RenderGlobal.renderEntities}.
 */
public final class ParticleCulling {

    /** Blocks around a particle's box its quad may reach (a large explosion is about 0.8). */
    private static final double MARGIN = 1.0;

    private static Frustum frustum;
    private static final Long2IntOpenHashMap LIGHT = new Long2IntOpenHashMap(1024);
    private static Map<String, Float> chances;
    private static float[] chanceById = new float[0];

    private ParticleCulling() {}

    private static boolean culling() {
        return Initializer.CONFIG.particleCulling && Initializer.isVulkanActive();
    }

    public static void setCamera(ICamera camera) {
        frustum = camera instanceof Frustum f ? f : null;
    }

    /** False for a particle whose quad cannot be on screen. */
    public static boolean visible(Particle particle) {
        Frustum f = frustum;
        if (f == null || !culling()) return true;
        AxisAlignedBB b = particle.getBoundingBox();
        return f.isBoxInFrustum(b.minX - MARGIN, b.minY - MARGIN, b.minZ - MARGIN, b.maxX + MARGIN, b.maxY + MARGIN, b.maxZ + MARGIN);
    }

    /** False when the particles of a block broken at {@code pos} would start outside the view. */
    public static boolean breakParticles(BlockPos pos) {
        Frustum f = frustum;
        if (f == null || !culling()) return true;
        return f.isBoxInFrustum(pos.getX() - MARGIN, pos.getY() - MARGIN, pos.getZ() - MARGIN,
                pos.getX() + 1 + MARGIN, pos.getY() + 1 + MARGIN, pos.getZ() + 1 + MARGIN);
    }

    /** Head of {@code ParticleManager.updateEffects}: light may have changed during the tick. */
    public static void newTick() {
        if (LIGHT.size() > 1 << 16) LIGHT.trim(1024);
        LIGHT.clear();
    }

    /**
     * {@code Particle.getBrightnessForRender} (the block the particle is in) from the per-tick
     * cache, or -1 to leave it to vanilla (not the client thread).
     */
    public static int light(World world, double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Initializer.isVulkanActive() || world != mc.world || !mc.isCallingFromMinecraftThread()) return -1;
        int bx = MathHelper.floor(x), by = MathHelper.floor(y), bz = MathHelper.floor(z);
        long key = ((long) bx & 0x3FFFFFFL) << 38 | ((long) by & 0xFFFL) << 26 | ((long) bz & 0x3FFFFFFL);
        int light = LIGHT.getOrDefault(key, -1);
        if (light < 0) {
            BlockPos pos = new BlockPos(bx, by, bz);
            light = world.isBlockLoaded(pos) ? world.getCombinedLight(pos, 0) : 0;
            LIGHT.put(key, light);
        }
        return light;
    }

    /** False when a particle of this id loses its spawn roll. */
    public static boolean spawn(int particleId) {
        Map<String, Float> map = Initializer.CONFIG.particleSpawnChance;
        if (map == null || map.isEmpty()) return true;
        if (map != chances) {
            float[] byId = new float[EnumParticleTypes.values().length + 64];
            java.util.Arrays.fill(byId, 1.0f);
            for (EnumParticleTypes type : EnumParticleTypes.values()) {
                Float chance = map.get(type.getParticleName());
                if (chance != null && type.getParticleID() < byId.length) byId[type.getParticleID()] = chance;
            }
            chanceById = byId;
            chances = map;
        }
        if (particleId < 0 || particleId >= chanceById.length) return true;
        float chance = chanceById[particleId];
        return chance >= 1.0f || ThreadLocalRandom.current().nextFloat() < chance;
    }
}
