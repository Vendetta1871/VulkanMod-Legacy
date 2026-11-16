package net.vesu.weather;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Random;

/**
 * Spawns the weather particles. Three sources: the sky (points on a sphere around the camera),
 * the surface (random columns in a wide radius) and block faces (the samples vanilla's ambient
 * block-particle pass picks every tick, see {@link #onAmbientSample}).
 */
public final class Spawner {

    private static final Random random = new Random();
    private static World lastWorld;
    private static int skyIdle, surfaceIdle, blockIdle;
    private static int afterWeather;
    private static boolean wasRaining;

    private Spawner() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.register(Spawner.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world != lastWorld) {
            lastWorld = world;
            Weather.clear();
            afterWeather = 0;
            wasRaining = world != null && world.isRaining();
        }
        if (world == null || mc.isGamePaused() || mc.getRenderViewEntity() == null || !Weather.enabled()) return;

        boolean raining = world.isRaining();
        if (raining != wasRaining) {
            afterWeather = raining ? 0 : random.nextInt(6000);
            wasRaining = raining;
        }
        if (Weather.live < Weather.PARTICLE_AMOUNT) {
            sky(mc, world);
            surface(mc, world);
        }
        if (afterWeather > 0) afterWeather--;
        Weather.tickTracked(world);
    }

    private static boolean weatherOk(WeatherDef d, World world) {
        return d.weather.current(world.getRainStrength(1.0f), world.getThunderStrength(1.0f), afterWeather);
    }

    // --- sky ---

    private static void sky(Minecraft mc, World world) {
        Entity cam = mc.getRenderViewEntity();
        double cx = cam.posX, cy = cam.posY + cam.getEyeHeight(), cz = cam.posZ;
        double vx = cam.posX - cam.lastTickPosX, vy = cam.posY - cam.lastTickPosY, vz = cam.posZ - cam.lastTickPosZ;
        double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
        int attempts;
        if (skyIdle <= 0) {
            attempts = 4;
        } else {
            float n = Weather.PARTICLE_AMOUNT / 25.0f;
            n = n + (n * Weather.STORM_DENSITY - n) * world.getThunderStrength(1.0f);
            attempts = (int) Math.floor((int) n * (1.0 + 2.0 * v));
        }
        if (skyIdle > 0) skyIdle--;
        float r = Weather.PARTICLE_DISTANCE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < attempts; i++) {
            double h;
            if (v < 0.8) {
                double a = random.nextDouble(), b = random.nextDouble();
                h = 1.0 - Math.abs(a * a - b * b);
            } else {
                h = random.nextDouble();
            }
            double theta = 2.0 * Math.PI * random.nextDouble();
            double phi = Math.acos(2.0 * h - 1.0);
            double x = cx + r * Math.sin(phi) * Math.cos(theta);
            double y = cy + r * Math.cos(phi);
            double z = cz + r * Math.sin(phi) * Math.sin(theta);
            pos.setPos(x, y, z);
            if (!world.isBlockLoaded(pos)) continue;
            int surface = Weather.height(world, MathHelper.floor(x), MathHelper.floor(z));
            if (surface >= MathHelper.floor(y)) continue;
            BlockPos ground = new BlockPos(x, surface - 1, z);
            WeatherDef.Precip precip = Weather.precipitation(world, ground);
            Biome biome = world.getBiome(pos);
            IBlockState groundState = world.getBlockState(ground);
            float c = random.nextFloat();
            for (WeatherDef d : WeatherDef.BUILT_IN) {
                if (d.spawnPos != WeatherDef.SpawnPos.SKY || !(d.density > c) || !weatherOk(d, world)) continue;
                if (!d.precipitation.contains(precip) || !d.biomes.test(biome) || !d.blocks.test(groundState)) continue;
                spawn(mc, world, d, x, y, z, true);
                skyIdle = 100;
            }
        }
    }

    // --- surface ---

    private static void surface(Minecraft mc, World world) {
        Entity cam = mc.getRenderViewEntity();
        int attempts = surfaceIdle > 0 ? Weather.PARTICLE_AMOUNT / 200 : 1;
        if (surfaceIdle > 0) surfaceIdle--;
        for (int i = 0; i < attempts; i++) {
            double x = cam.posX + Weather.SURFACE_RANGE * (random.nextDouble() - random.nextDouble());
            double z = cam.posZ + Weather.SURFACE_RANGE * (random.nextDouble() - random.nextDouble());
            int y = Weather.height(world, MathHelper.floor(x), MathHelper.floor(z));
            BlockPos ground = new BlockPos(x, y - 1, z);
            if (!world.isBlockLoaded(ground)) continue;
            Biome biome = world.getBiome(ground);
            WeatherDef.Precip precip = Weather.precipitation(world, ground);
            IBlockState state = world.getBlockState(ground);
            float c = random.nextFloat();
            for (WeatherDef d : WeatherDef.BUILT_IN) {
                if (d.spawnPos != WeatherDef.SpawnPos.WORLD_SURFACE || !(d.density > c) || !weatherOk(d, world)) continue;
                if (!d.precipitation.contains(precip) || !d.biomes.test(biome) || !d.blocks.test(state)) continue;
                spawn(mc, world, d, x, y + random.nextDouble() + 0.1, z, false);
                surfaceIdle = 100;
            }
        }
    }

    // --- block faces ---

    /** One sample of vanilla's ambient block pass ({@code WorldClient.showBarrierParticles}). */
    public static void onAmbientSample(World world, BlockPos sample) {
        if (!Weather.enabled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (blockIdle <= 0 && random.nextFloat() < 0.9f) return;
        blockIdle--;
        BlockPos s = sample.toImmutable();
        IBlockState open = world.getBlockState(s);
        if (open.getCollisionBoundingBox(world, s) != null && !WeatherDef.weatherIgnored(open)) return;
        WeatherDef.Precip precip = Weather.precipitation(world, s);
        Biome biome = world.getBiome(s);
        for (WeatherDef d : WeatherDef.BUILT_IN) {
            EnumFacing dir;
            switch (d.spawnPos) {
                case BLOCK_SIDES: dir = EnumFacing.HORIZONTALS[random.nextInt(4)]; break;
                case BLOCK_BOTTOM: dir = EnumFacing.DOWN; break;
                case BLOCK_TOP: dir = EnumFacing.UP; break;
                default: continue;
            }
            if (!weatherOk(d, world)) continue;
            BlockPos t = s.offset(dir.getOpposite());
            IBlockState target = world.getBlockState(t);
            AxisAlignedBB box = target.getCollisionBoundingBox(world, t);
            boolean fluid = Weather.hasFluid(target);
            if (box == null && !fluid) continue;
            if (!d.precipitation.contains(precip) || !(d.density > random.nextFloat()) || !d.biomes.test(biome)) continue;
            if (!d.blocks.test(target) && !d.blocks.test(open)) continue;
            if (d.needsSkyAccess && s.getY() < Weather.height(world, s.getX(), s.getZ())) continue;

            // A point on the face of T that looks at S, on T's actual surface.
            double a = random.nextDouble(), b = random.nextDouble();
            double x, y, z;
            if (dir == EnumFacing.UP) {
                double top = box != null ? box.maxY : 0.0;
                if (fluid) top = Math.max(top, Weather.fluidTop(target));
                if (box != null && !fluid && (a < box.minX || a > box.maxX || b < box.minZ || b > box.maxZ)) continue;
                x = t.getX() + a; y = t.getY() + top + 0.01; z = t.getZ() + b;
            } else if (dir == EnumFacing.DOWN) {
                if (box == null) continue;
                x = t.getX() + a; y = t.getY() + box.minY - 0.01; z = t.getZ() + b;
            } else {
                if (box == null) continue;
                double py = box.minY + (box.maxY - box.minY) * a;
                double along = b;
                switch (dir) {
                    case EAST: x = t.getX() + box.maxX + 0.01; z = t.getZ() + along; break;
                    case WEST: x = t.getX() + box.minX - 0.01; z = t.getZ() + along; break;
                    case SOUTH: z = t.getZ() + box.maxZ + 0.01; x = t.getX() + along; break;
                    default: z = t.getZ() + box.minZ - 0.01; x = t.getX() + along; break;
                }
                if (dir.getAxis() == EnumFacing.Axis.X && (along < box.minZ || along > box.maxZ)) continue;
                if (dir.getAxis() == EnumFacing.Axis.Z && (along < box.minX || along > box.maxX)) continue;
                y = t.getY() + py;
            }
            if (d.style == WeatherDef.Style.STREAK) {
                WeatherDef rain = WeatherDef.BUILT_IN.get(0);
                float[] tint = Weather.tint(world, rain.tint, x, y, z, random);
                mc.effectRenderer.addEffect(new StreakParticle(world, x, y, z, dir, d.blocks, tint));
            } else {
                spawn(mc, world, d, x, y, z, false);
            }
            blockIdle = 10000;
        }
    }

    private static void spawn(Minecraft mc, World world, WeatherDef d, double x, double y, double z, boolean sky) {
        if (d.style == WeatherDef.Style.REGISTERED) {
            Particle p = mc.effectRenderer.spawnEffectParticle(d.registeredParticle, x, y, z, 0.0, 0.0, 0.0);
            if (p == null) return;
            float k = Weather.windMultiplier(world, d);
            if (k != 0.0f) {
                long time = world.getTotalWorldTime();
                net.vesu.mixin.render.ParticleMotionAccessor m = (net.vesu.mixin.render.ParticleMotionAccessor) p;
                m.vesu$setWmx(m.vesu$wmx() + Weather.windX(x, y, time) * k * 10.0f);
                m.vesu$setWmz(m.vesu$wmz() + Weather.windZ(z, y, time) * k * 10.0f);
                Weather.track(p, d);
            }
            return;
        }
        mc.effectRenderer.addEffect(new WeatherParticle(world, x, y, z, d, sky));
    }
}
