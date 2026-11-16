package net.vesu.weather;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeColorHelper;
import net.vesu.Initializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Precipitation "Particle": rain, snow and the ambient weather effects as real particles, after
 * the Particle Rain mod (reimplemented from a behavioral description). This class holds the
 * global settings and the world queries everything shares: the weather surface height, the
 * precipitation type, the wind field and the tints; {@link Spawner} spawns, {@link WeatherParticle}
 * and {@link StreakParticle} simulate and draw, {@link WeatherSprites} builds the sprites.
 */
public final class Weather {

    // --- settings (the mod's defaults) ---
    static final int PARTICLE_AMOUNT = 1500;
    static final float STORM_DENSITY = 1.5f;
    static final float PARTICLE_DISTANCE = 16.0f;
    static final float SURFACE_RANGE = 100.0f;
    static final float WIND_STRENGTH = 0.03f, GUST_FREQUENCY = 0.15f, MODULATION_SPEED = 0.1f;
    static final float TINT_MIX = 0.6f;
    static final float STREAK_OPACITY = 0.9f, STREAK_SIZE = 0.5f;
    static final int WATER_COLOR = 0x3F76E4, FOG_COLOR = 0xC0D8FF;

    /** Live mod particles (CUSTOM, block model, streak). */
    static int live;

    private static final Long2IntOpenHashMap HEIGHTS = new Long2IntOpenHashMap();
    private static long heightsTick = Long.MIN_VALUE;
    private static final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    private Weather() {}

    public static boolean enabled() {
        return "particle".equals(Initializer.CONFIG.effectivePrecipitation()) && Initializer.isVulkanActive();
    }

    // --- surface height and precipitation ---

    /**
     * Y of the first free block above the first surface that stops the weather in column (x, z):
     * the precipitation height, walked down past fences, signs and barriers. Cached per column for
     * 80 game ticks.
     */
    static int height(World world, int x, int z) {
        long now = world.getTotalWorldTime();
        if (now - heightsTick >= 80 || now < heightsTick) {
            HEIGHTS.clear();
            heightsTick = now;
        }
        long key = (long) x << 32 | (z & 0xFFFFFFFFL);
        int cached = HEIGHTS.getOrDefault(key, Integer.MIN_VALUE);
        if (cached != Integer.MIN_VALUE) return cached;
        scratch.setPos(x, 0, z);
        int y = world.getPrecipitationHeight(scratch).getY();
        if (y <= 0) y = 255;
        for (; y >= 0; y--) {
            scratch.setPos(x, y, z);
            IBlockState s = world.getBlockState(scratch);
            if (s.getMaterial().isLiquid()) break;
            if (s.getCollisionBoundingBox(world, scratch) != null && !WeatherDef.weatherIgnored(s)) break;
        }
        int h = y + 1;
        HEIGHTS.put(key, h);
        return h;
    }

    static WeatherDef.Precip precipitation(World world, BlockPos pos) {
        Biome biome = world.getBiome(pos);
        if (!biome.canRain() && !biome.getEnableSnow()) return WeatherDef.Precip.NONE;
        return biome.getTemperature(pos) < 0.15f ? WeatherDef.Precip.SNOW : WeatherDef.Precip.RAIN;
    }

    // --- wind ---

    /** Wind x/z at a position (blocks/tick before the definition's multiplier). */
    static float windX(double x, double y, long time) {
        return gust(x, time) * heightFactor(y);
    }

    static float windZ(double z, double y, long time) {
        return gust(z, time) * heightFactor(y);
    }

    private static float heightFactor(double y) {
        return MathHelper.clamp((float) (y - 50.0) / 40.0f, 0.0f, 1.0f);
    }

    private static float gust(double u, long time) {
        double shift = time * (double) MODULATION_SPEED;
        double a = u * GUST_FREQUENCY + shift;
        return (float) (-WIND_STRENGTH * (Math.sin(a) * Math.sin(0.5 * a) + 1.0));
    }

    /** The definition's wind multiplier for the current weather. */
    static float windMultiplier(World world, WeatherDef def) {
        return world.getThunderStrength(1.0f) > 0.9f ? def.stormWindStrength : def.windStrength;
    }

    // --- tints ---

    /** RGB of the tint at a position, or white. */
    static float[] tint(World world, WeatherDef.Tint tint, double x, double y, double z, java.util.Random rand) {
        BlockPos pos = new BlockPos(x, y, z);
        switch (tint) {
            case WATER: {
                if (net.vesu.shader.PackManager.active() != null) return new float[]{1, 1, 1};
                int mul = BiomeColorHelper.getWaterColorAtPos(world, pos);
                float[] out = new float[3];
                for (int c = 0; c < 3; c++) {
                    int shift = 16 - 8 * c;
                    float water = ((WATER_COLOR >> shift & 255) * (mul >> shift & 255) / 255.0f) / 255.0f;
                    float fog = (FOG_COLOR >> shift & 255) / 255.0f;
                    out[c] = water + (fog - water) * TINT_MIX;
                }
                return out;
            }
            case FOG: {
                float k = 0.7f + 0.2f * (1.0f - world.getRainStrength(1.0f));
                return new float[]{(FOG_COLOR >> 16 & 255) / 255.0f * k, (FOG_COLOR >> 8 & 255) / 255.0f * k,
                        (FOG_COLOR & 255) / 255.0f * k};
            }
            case MAP: {
                BlockPos top = world.getPrecipitationHeight(pos).down();
                IBlockState s = world.getBlockState(top);
                int c = s.getMapColor(world, top).colorValue;
                float k = 220.0f / 255.0f * (0.8f + 0.2f * rand.nextFloat());
                return new float[]{(c >> 16 & 255) / 255.0f * k, (c >> 8 & 255) / 255.0f * k, (c & 255) / 255.0f * k};
            }
            default:
                return new float[]{1, 1, 1};
        }
    }

    /** Height of the fluid surface in a liquid block (1 for anything else). */
    static double fluidTop(IBlockState s) {
        if (s.getBlock() instanceof BlockLiquid) {
            return 1.0 - BlockLiquid.getLiquidHeightPercent(s.getValue(BlockLiquid.LEVEL));
        }
        return 1.0;
    }

    static boolean fluidSource(IBlockState s) {
        return s.getBlock() instanceof BlockLiquid && s.getValue(BlockLiquid.LEVEL) == 0;
    }

    static boolean hasFluid(IBlockState s) {
        return s.getMaterial().isLiquid();
    }

    // --- vanilla particles pushed by the wind ---

    private static final List<Object[]> tracked = new ArrayList<>();

    static void track(Particle p, WeatherDef def) {
        tracked.add(new Object[]{p, def, new int[]{6000}});
    }

    static void tickTracked(World world) {
        long time = world.getTotalWorldTime();
        for (int i = tracked.size() - 1; i >= 0; i--) {
            Object[] e = tracked.get(i);
            Particle p = (Particle) e[0];
            int[] left = (int[]) e[2];
            if (!p.isAlive() || --left[0] <= 0) {
                tracked.remove(i);
                continue;
            }
            float k = windMultiplier(world, (WeatherDef) e[1]);
            if (k == 0.0f) continue;
            net.vesu.mixin.render.ParticleMotionAccessor m = (net.vesu.mixin.render.ParticleMotionAccessor) p;
            double y = m.vesu$wy();
            m.vesu$setWmx(m.vesu$wmx() + windX(m.vesu$wx(), y, time) * k);
            m.vesu$setWmz(m.vesu$wmz() + windZ(m.vesu$wz(), y, time) * k);
        }
    }

    static void clear() {
        tracked.clear();
        live = 0;
        HEIGHTS.clear();
    }

    static Minecraft mc() {
        return Minecraft.getMinecraft();
    }
}
