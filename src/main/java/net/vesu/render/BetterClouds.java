package net.vesu.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumUsage;
import net.minecraft.entity.Entity;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.NoiseGeneratorSimplex;
import net.vesu.Initializer;
import net.vesu.vulkan.Drawer;
import net.vesu.vulkan.Pipeline;
import net.vesu.vulkan.VRenderSystem;
import net.vesu.vulkan.memory.MemoryTypes;
import net.vesu.vulkan.memory.VertexBuffer;
import net.vesu.vulkan.texture.VTextureSelector;
import net.vesu.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cloud quality "Better": a field of many small, semi-transparent, axis-aligned cloud cubes,
 * after the look and behaviour of the Better Clouds mod (clean-room, from a behavioural
 * specification).
 *
 * <p>A background generator samples a 2-D density field (region-blended simplex octave stacks,
 * cloudiness remap, kilometre-scale coverage mask and hash-based edge fuzz) on a jittered
 * 5.25-block grid and places one 16x8x16 cube per positive cell, higher where the field is
 * denser. Cubes live in "cloud space", which drifts along a long pre-baked random path; the
 * field is regenerated when the camera moved about three 64-block chunks relative to it or the
 * cloudiness changed by 1%. The cubes are drawn instanced ({@code clouds_better}); the vertex
 * shader wobbles their size with a scrolling noise texture and shrinks them with distance, the
 * fragment shader colours them from a light-gradient texture indexed by the view/sun angle and
 * the time of day, with a sun/moon halo and a tint derived from vanilla's cloud and fog colour.
 * At 0.2 alpha per cube the overlapping cubes read as soft, volumetric clouds.</p>
 */
public final class BetterClouds {

    /** Instance record: cube center relative to the task block origin (y = height above the base). */
    public static final VertexFormat FORMAT = new VertexFormat()
            .addElement(new VertexFormatElement(0, EnumType.FLOAT, EnumUsage.POSITION, 3));
    private static final int STRIDE = 12;
    private static final int CUBE_VERTICES = 36;

    // --- configuration: the mod's defaults (main config and the "default" presets) ---

    private static final int CHUNK_SIZE = 64;
    private static final double SPACING = 5.25;
    private static final float SIZE_XZ = 16.0f, SIZE_Y = 8.0f;
    private static final float Y_RANGE = 64.0f, Y_OFFSET = 0.0f;
    private static final double RANDOM_PLACEMENT = 1.0, FUZZINESS = 1.0;
    private static final double SPARSITY = 0.0, BOTTOM_SPARSITY = 0.0, SAMPLING_SCALE = 1.0;
    private static final double TRAVEL_SPEED = 0.03;
    private static final float WIND_EFFECT = 1.0f, WIND_SPEED = 0.8f;
    private static final boolean HALO = true, NEAR_FADE = false;
    private static final float SUN_HALO_SIZE = 1.0f, MOON_HALO_SIZE = 1.0f;
    private static final float SCALE_FALLOFF_MIN = 0.25f;
    private static final float FOG_DENSITY = 1.0f, FOG_END_FACTOR = 1.0f;
    private static final float WORLD_CURVATURE = 0.0f;
    private static final boolean FRUSTUM_CULLING = true;

    private static final float GAMMA = 1.0f, SUN_PATH_ANGLE_DEG = 0.0f;
    private static final float RISE_START = -785, RISE_END = 1163, SET_START = 10837, SET_END = 12785;
    private static final float REF_RISE_START = -785, REF_RISE_END = 1163, REF_SET_START = 10837, REF_SET_END = 12785;
    private static final float DAY_BRIGHTNESS = 1.0f, NIGHT_BRIGHTNESS = 1.0f, SATURATION = 1.0f;
    private static final float OPACITY = 0.2f, OPACITY_FACTOR = 1.0f, OPACITY_EXPONENT = 1.5f;
    private static final float[] TOP_COLOR = {1.0f, 1.0f, 1.0f};
    private static final float[] BOTTOM_COLOR = {0.75f, 0.75f, 0.75f};
    private static final float COLOR_TRANSITION_END = 0.5f;

    /** Noise preset "default": varied patches, a different pattern per ~2 km region. */
    private static final int[][] OCTAVE_SETS = {{-1, 0, 1, 2}, {-2, -1, 0, 1, 2}, {-2, 0, 1, 2}, {0, 1, 2}};

    private static final float[] MOON_PHASE_FACTORS = {1.0f, 0.75f, 0.5f, 0.25f, 0.0f, 0.25f, 0.5f, 0.75f};

    // --- render state ---

    private static Pipeline pipeline;
    private static final Pipeline.BlendState BLEND = new Pipeline.BlendState(0x0302, 0x0303, 1, 0x0303);
    private static VulkanImage noiseTexture;
    private static VulkanImage lightTexture;

    /** UBO fields {@code BcVert} / {@code BcFrag}, see clouds_better.vsh / .fsh. */
    private static final float[] VERT = new float[20];
    private static final float[] FRAG = new float[20];

    private static World lastWorld;
    private static long seed;
    private static Generator generator;

    /** Latest requested task: its chunk, cloudiness percent and distance. */
    private static boolean requested;
    private static int reqCX, reqCZ, reqPct, reqDistance;
    private static int nextTaskId;

    /** The displayed generation: its task, instance buffer and per-chunk culling runs. */
    private static Result displayed;
    private static VertexBuffer instanceBuffer;
    private static int[] runs = new int[64];

    /** Cloud-tick bookkeeping for the interpolated clock (spec 4.1). */
    private static long lastTotalTime = Long.MIN_VALUE, lastTickWorldTime;
    private static long ticksPerClientTick;

    private BetterClouds() {}

    public static void init() {
        pipeline = new Pipeline(FORMAT, "clouds_better");
        pipeline.setDepthClamp(true);
    }

    public static float[] vertParams() {
        return VERT;
    }

    public static float[] fragParams() {
        return FRAG;
    }

    /**
     * Draws the cube field. The caller (CloudRenderer) has set the projection (depth clamped)
     * and the fog snapshot; {@code headMV} is the camera rotation without translation.
     * {@code eyeX/Y/Z} is the render entity position vanilla hands renderClouds.
     *
     * @return true when vanilla clouds stay suppressed this frame
     */
    public static boolean render(World world, Matrix4f headMV, float partialTicks, int cloudTickCounter,
                                 double eyeX, double eyeY, double eyeZ) {
        if (pipeline == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc.getRenderViewEntity();
        double camX = eyeX, camY = eyeY + (viewer != null ? viewer.getEyeHeight() : 0.0f), camZ = eyeZ;

        if (world != lastWorld) {
            resetWorld(world);
        }
        ensureTextures();

        // --- clocks (time source WORLD) ---
        long worldTime = world.getWorldTime();
        long totalTime = world.getTotalWorldTime();
        if (totalTime != lastTotalTime) {
            long elapsed = lastTotalTime == Long.MIN_VALUE ? 0 : worldTime - lastTickWorldTime;
            // A /time set jump is not interpolated across.
            ticksPerClientTick = elapsed >= 0 && elapsed <= 100 ? elapsed : 0;
            lastTotalTime = totalTime;
            lastTickWorldTime = worldTime;
        }
        long trueTicks = Math.floorMod(worldTime, 2147483647L);
        long renderTicks = trueTicks % 320000L;
        double tickDelta = partialTicks * (double) ticksPerClientTick;

        // --- travel origin and cloudiness ---
        double s = Math.max(0.0, (trueTicks + tickDelta) * TRAVEL_SPEED / 20.0);
        double[] origin = BetterCloudsData.pathAt(s);
        double ox = origin[0], oz = origin[1];

        float rain = world.getRainStrength(partialTicks);
        float thunder = world.getThunderStrength(partialTicks);
        float cloudiness = MathHelper.clamp((Math.max(0.6f * rain, thunder) * 0.3f + 0.5f), 0.0f, 1.0f);
        int pct = MathHelper.ceil(100.0f * cloudiness);

        // --- generator update (spec 3.6) ---
        int distance = blockDistance(mc);
        int cx = MathHelper.floor((camX - ox) / CHUNK_SIZE);
        int cz = MathHelper.floor((camZ - oz) / CHUNK_SIZE);
        if (!requested || Math.abs(cx - reqCX) + Math.abs(cz - reqCZ) > 2 || pct != reqPct
                || distance != reqDistance) {
            requested = true;
            reqCX = cx;
            reqCZ = cz;
            reqPct = pct;
            reqDistance = distance;
            generator.submit(new Task(++nextTaskId, seed, cx, cz, cloudiness, pct, distance));
        }
        Result done = generator.completed.getAndSet(null);
        if (done != null && (displayed == null || done.taskId > displayed.taskId)) {
            swapIn(done);
        }

        // Camera inside a fluid: no clouds (they looked wrong through unloaded water chunks).
        if (viewer != null && ActiveRenderInfo.getBlockStateAtEntityViewpoint(world, viewer, partialTicks)
                .getMaterial().isLiquid()) {
            return true;
        }
        if (displayed == null || displayed.count == 0 || instanceBuffer == null) {
            return true;
        }

        // --- uniforms ---
        Result r = displayed;
        float cloudHeight = world.provider.getCloudHeight() + Y_OFFSET;
        double roX = (double) r.cx * CHUNK_SIZE - camX + ox;
        double roY = cloudHeight - camY;
        double roZ = (double) r.cz * CHUNK_SIZE - camZ + oz;
        float shaderTime = (float) (renderTicks / 20.0 + tickDelta / 20.0);
        float fogEnd = distance * FOG_END_FACTOR;
        float bandHeight = Y_RANGE + SIZE_Y;

        VERT[0] = SIZE_XZ; VERT[1] = SIZE_Y; VERT[2] = shaderTime; VERT[3] = COLOR_TRANSITION_END;
        VERT[4] = (float) roX; VERT[5] = (float) roY; VERT[6] = (float) roZ;
        VERT[7] = r.distance - CHUNK_SIZE / 2.0f;
        VERT[8] = (float) camX; VERT[9] = (float) camZ; VERT[10] = bandHeight; VERT[11] = SCALE_FALLOFF_MIN;
        VERT[12] = WIND_EFFECT; VERT[13] = WIND_SPEED; VERT[14] = FOG_DENSITY; VERT[15] = fogEnd;
        VERT[16] = curvature(); VERT[17] = NEAR_FADE ? 1.0f : 0.0f; VERT[18] = 0.0f; VERT[19] = 0.0f;

        computeFragmentUniforms(world, mc, partialTicks, worldTime, tickDelta);

        // --- culling runs (spec 8.3) ---
        Matrix4f projView = new Matrix4f().set(VRenderSystem.getProjectionMatrix4f()).mul(headMV);
        int runCount = buildRuns(r, projView, ox - camX, cloudHeight - camY, oz - camZ);
        if (runCount == 0) {
            return true;
        }

        // --- draw ---
        Matrix4f mv = new Matrix4f(headMV).translate((float) roX, (float) roY, (float) roZ);
        VRenderSystem.applyModelViewMatrix(mv);
        VRenderSystem.calculateMVP();
        VRenderSystem.setShaderPipeline(pipeline);
        Drawer.currentBlendState = BLEND;
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthFunc(515); // GL_LEQUAL: not behind the stored depth
        VRenderSystem.depthMask(OPACITY > 0.975f);
        VRenderSystem.disableCull();
        VulkanImage savedLight = VTextureSelector.getLightTexture();
        VTextureSelector.bindTexture(noiseTexture);
        VTextureSelector.setLightTexture(lightTexture);
        try {
            Drawer.getInstance().drawInstancedRuns(instanceBuffer, CUBE_VERTICES, runs, runCount);
        } finally {
            VTextureSelector.setLightTexture(savedLight);
        }
        return true;
    }

    /** Generation radius in blocks: twice the render distance, kept within 384..768. */
    private static int blockDistance(Minecraft mc) {
        return MathHelper.clamp(mc.gameSettings.renderDistanceChunks * 32, 384, 768);
    }

    private static float curvature() {
        if (WORLD_CURVATURE == 0.0f) return 0.0f;
        return (float) (Math.pow(2.0, Math.abs(WORLD_CURVATURE)) * Math.signum(WORLD_CURVATURE));
    }

    private static void resetWorld(World world) {
        lastWorld = world;
        seed = worldSeed(world);
        if (generator == null) {
            generator = new Generator();
        }
        generator.cancelAll();
        requested = false;
        displayed = null;
        if (instanceBuffer != null) {
            instanceBuffer.freeBuffer();
            instanceBuffer = null;
        }
        lastTotalTime = Long.MIN_VALUE;
        ticksPerClientTick = 0;
    }

    /**
     * 1.12 clients do not receive the world seed. In singleplayer the integrated server knows
     * it; on a server a hash of its address stands in. Mixed with the dimension id.
     */
    private static long worldSeed(World world) {
        Minecraft mc = Minecraft.getMinecraft();
        long base = 0L;
        try {
            IntegratedServer server = mc.getIntegratedServer();
            WorldServer ws = server != null ? server.getWorld(0) : null;
            if (ws != null) {
                base = ws.getSeed();
            } else {
                ServerData data = mc.getCurrentServerData();
                if (data != null && data.serverIP != null) base = data.serverIP.hashCode();
            }
        } catch (Throwable ignored) {
            // keep 0: the seed only changes the pattern
        }
        return hash(base, world.provider.getDimension());
    }

    /** Swaps a finished generation in and uploads its instances into a fresh device buffer. */
    private static void swapIn(Result done) {
        VertexBuffer old = instanceBuffer;
        instanceBuffer = null;
        if (done.count > 0) {
            ByteBuffer data = MemoryUtil.memAlloc(done.count * STRIDE);
            data.asFloatBuffer().put(done.positions, 0, done.count * 3);
            data.position(0).limit(done.count * STRIDE);
            VertexBuffer vb = new VertexBuffer(done.count * STRIDE, MemoryTypes.GPU_MEM);
            vb.copyToVertexBuffer(STRIDE, done.count, data);
            MemoryUtil.memFree(data);
            instanceBuffer = vb;
        }
        if (old != null) {
            old.freeBuffer();
        }
        displayed = done;
    }

    /**
     * Frustum-culls the generator chunks (side planes only: the projection's depth range is
     * overridden and clamped) and merges consecutive visible chunks into draw runs.
     * {@code offX/Y/Z} moves a cloud-space position into camera-relative space.
     */
    private static int buildRuns(Result r, Matrix4f m, double offX, double offY, double offZ) {
        int n = 0;
        boolean open = false;
        int chunks = r.chunkFirst.length;
        if (runs.length < 2 * chunks) runs = new int[2 * chunks];
        boolean cull = FRUSTUM_CULLING && WORLD_CURVATURE == 0.0f;
        for (int i = 0; i < chunks; i++) {
            boolean visible = true;
            if (cull) {
                float[] b = r.bounds;
                int o = 6 * i;
                visible = boxVisible(m,
                        (float) (b[o] + offX - SIZE_XZ), (float) (Math.min(b[o + 1], 0.0f) + offY - SIZE_Y),
                        (float) (b[o + 2] + offZ - SIZE_XZ),
                        (float) (b[o + 3] + offX + SIZE_XZ), (float) (b[o + 4] + offY + SIZE_Y),
                        (float) (b[o + 5] + offZ + SIZE_XZ));
            }
            if (visible) {
                if (open) {
                    runs[2 * n - 1] += r.chunkCount[i];
                } else {
                    runs[2 * n] = r.chunkFirst[i];
                    runs[2 * n + 1] = r.chunkCount[i];
                    n++;
                    open = true;
                }
            } else {
                open = false;
            }
        }
        return n;
    }

    /** Box against the left/right/bottom/top clip planes of {@code m} (positive-vertex test). */
    private static boolean boxVisible(Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1) {
        for (int p = 0; p < 4; p++) {
            float sign = (p & 1) == 0 ? 1.0f : -1.0f;
            int row = p >> 1; // 0: x, 1: y
            float a = m.m03() + sign * (row == 0 ? m.m00() : m.m01());
            float b = m.m13() + sign * (row == 0 ? m.m10() : m.m11());
            float c = m.m23() + sign * (row == 0 ? m.m20() : m.m21());
            float d = m.m33() + sign * (row == 0 ? m.m30() : m.m31());
            float px = a >= 0 ? x1 : x0, py = b >= 0 ? y1 : y0, pz = c >= 0 ? z1 : z0;
            if (a * px + b * py + c * pz + d < 0.0f) return false;
        }
        return true;
    }

    // --- per-frame values (spec 6) ---

    private static void computeFragmentUniforms(World world, Minecraft mc, float partialTicks,
                                                long worldTime, double tickDelta) {
        float dayTime = (float) (Math.floorMod(worldTime, 24000L) + tickDelta) % 24000.0f;

        // Day/night factor and the light-texture time, remapped onto the reference windows.
        float dayFactor;
        float mapped;
        if (dayTime <= 6000.0f || dayTime > 18000.0f) {
            float t = dayTime > 18000.0f ? dayTime - 24000.0f : dayTime;
            dayFactor = smoothstep(RISE_START, RISE_END, t);
            if (t < RISE_START) mapped = remap(t, -6000, RISE_START, -6000, REF_RISE_START);
            else if (t < RISE_END) mapped = remap(t, RISE_START, RISE_END, REF_RISE_START, REF_RISE_END);
            else mapped = remap(t, RISE_END, 6000, REF_RISE_END, 6000);
        } else {
            dayFactor = 1.0f - smoothstep(SET_START, SET_END, dayTime);
            if (dayTime < SET_START) mapped = remap(dayTime, 6000, SET_START, 6000, REF_SET_START);
            else if (dayTime < SET_END) mapped = remap(dayTime, SET_START, SET_END, REF_SET_START, REF_SET_END);
            else mapped = remap(dayTime, SET_END, 18000, REF_SET_END, 18000);
        }
        float brightness = lerp(NIGHT_BRIGHTNESS, DAY_BRIGHTNESS, dayFactor);

        // Sun direction: (1,0,0) rotated by alpha + pi/2 about the sun-path axis (0, sin b, cos b).
        float alpha = world.getCelestialAngle(partialTicks) * (float) (Math.PI * 2.0);
        float beta = (float) Math.toRadians(SUN_PATH_ANGLE_DEG);
        float ay = MathHelper.sin(beta), az = MathHelper.cos(beta);
        float theta = alpha + (float) (Math.PI / 2.0);
        float ct = MathHelper.cos(theta), st = MathHelper.sin(theta);
        // Rodrigues with v = (1,0,0), k = (0, ay, az): k x v = (0, az, -ay), k.v = 0.
        float sunX = ct, sunY = st * az, sunZ = -st * ay;

        // Halo size: sun until the end of sunset, then the moon by phase.
        float halo;
        if (dayTime < SET_END) {
            halo = 2.05f - SUN_HALO_SIZE;
        } else {
            float factor = MOON_PHASE_FACTORS[Math.floorMod(world.getMoonPhase(), 8)];
            halo = (float) (5.0 * Math.pow(0.06217, MOON_HALO_SIZE * factor));
        }

        float[] tint = effectTint(world, mc, partialTicks, alpha);

        float gamma = GAMMA > 0.0f ? GAMMA : -1.0f / GAMMA;
        FRAG[0] = OPACITY; FRAG[1] = OPACITY_FACTOR; FRAG[2] = OPACITY_EXPONENT; FRAG[3] = brightness;
        FRAG[4] = gamma; FRAG[5] = SATURATION; FRAG[6] = halo; FRAG[7] = mapped / 24000.0f;
        FRAG[8] = TOP_COLOR[0] * tint[0]; FRAG[9] = TOP_COLOR[1] * tint[1]; FRAG[10] = TOP_COLOR[2] * tint[2];
        FRAG[11] = ay;
        FRAG[12] = BOTTOM_COLOR[0] * tint[0]; FRAG[13] = BOTTOM_COLOR[1] * tint[1];
        FRAG[14] = BOTTOM_COLOR[2] * tint[2]; FRAG[15] = az;
        FRAG[16] = sunX; FRAG[17] = sunY; FRAG[18] = sunZ; FRAG[19] = HALO ? 1.0f : 0.0f;
    }

    private static final float WR = 0.299f, WG = 0.587f, WB = 0.114f;

    /** Global colour multiplier from vanilla's cloud colour, the fog colour and night vision (spec 6.4). */
    private static float[] effectTint(World world, Minecraft mc, float partialTicks, float alpha) {
        Vec3d c0 = world.getCloudColour(partialTicks);
        float[] c = {(float) Math.pow(c0.x, 2.2), (float) Math.pow(c0.y, 2.2), (float) Math.pow(c0.z, 2.2)};
        float y = c[0] * WR + c[1] * WG + c[2] * WB;
        float[] k = y < 1e-4f ? new float[]{1, 1, 1} : new float[]{c[0] / y, c[1] / y, c[2] / y};

        float moon = MathHelper.clamp(-MathHelper.cos(alpha), -0.25f, 0.25f) * 2.0f + 0.5f;
        y += 0.65f * moon;

        float[] fog = VRenderSystem.getShaderFogColor();
        float[] cf = {(float) Math.pow(Math.max(fog[0], 0), 2.2), (float) Math.pow(Math.max(fog[1], 0), 2.2),
                (float) Math.pow(Math.max(fog[2], 0), 2.2)};
        float yf = cf[0] * WR + cf[1] * WG + cf[2] * WB;
        float[] kf = yf < 1e-4f ? new float[]{1, 1, 1} : new float[]{cf[0] / yf, cf[1] / yf, cf[2] / yf};
        for (int i = 0; i < 3; i++) k[i] = (float) Math.sqrt(k[i] * kf[i]);
        float sy = (float) (Math.sqrt(yf) + Math.sqrt(y));
        y = sy * sy / 4.0f;

        y = MathHelper.clamp(y / 0.9777f, 0.0f, 2.0f);
        float sat = (float) Math.pow(y, 1.0 / 2.2);
        float[] tint = new float[3];
        for (int i = 0; i < 3; i++) {
            float d = k[i] * sat + y * (1.0f - sat);
            tint[i] = (float) Math.pow(Math.min(d * y, 1.0f), 1.0 / 2.2);
        }

        float nightVision = nightVisionScale(mc, partialTicks);
        if (nightVision > 0.0f) {
            float min = Math.min(tint[0], Math.min(tint[1], tint[2]));
            float div = lerp(1.0f, min, nightVision);
            if (div > 1e-4f) {
                for (int i = 0; i < 3; i++) tint[i] /= div;
            }
        }
        return tint;
    }

    /** Vanilla's night-vision brightness (full, flickering during the last 10 seconds). */
    private static float nightVisionScale(Minecraft mc, float partialTicks) {
        if (mc.player == null) return 0.0f;
        PotionEffect effect = mc.player.getActivePotionEffect(MobEffects.NIGHT_VISION);
        if (effect == null) return 0.0f;
        int duration = effect.getDuration();
        return duration > 200 ? 1.0f
                : 0.7f + MathHelper.sin((duration - partialTicks) * (float) Math.PI * 0.2f) * 0.3f;
    }

    // --- textures (spec 9) ---

    private static void ensureTextures() {
        if (noiseTexture == null) {
            noiseTexture = upload(128, 128, BetterCloudsData.noiseTexture());
        }
        if (lightTexture == null) {
            lightTexture = upload(32, 32, BetterCloudsData.lightGradient());
        }
    }

    /** RGBA8, bilinear, repeat (the light gradient's U lookups stay inside the texel centers). */
    private static VulkanImage upload(int width, int height, byte[] rgba) {
        ByteBuffer pixels = MemoryUtil.memAlloc(rgba.length);
        pixels.put(rgba).flip();
        VulkanImage image = new VulkanImage(1, width, height, 4, true, false);
        image.uploadSubTextureAsync(0, width, height, 0, 0, 4, 0, 0, width, pixels);
        MemoryUtil.memFree(pixels);
        return image;
    }

    // --- generator (spec 3) ---

    private record Task(int id, long seed, int cx, int cz, float cloudiness, int pct, int distance) {}

    /** A finished generation: positions relative to the task block origin, per-chunk runs and bounds. */
    private static final class Result {
        final int taskId, cx, cz, distance, count;
        final float[] positions;
        final int[] chunkFirst, chunkCount;
        /** Per chunk: min xyz, max xyz of the cube centers, cloud-space global blocks. */
        final float[] bounds;

        Result(int taskId, int cx, int cz, int distance, int count, float[] positions,
               int[] chunkFirst, int[] chunkCount, float[] bounds) {
            this.taskId = taskId;
            this.cx = cx;
            this.cz = cz;
            this.distance = distance;
            this.count = count;
            this.positions = positions;
            this.chunkFirst = chunkFirst;
            this.chunkCount = chunkCount;
            this.bounds = bounds;
        }
    }

    /** Cube points of one 64x64-cell sample chunk, aligned to global cell multiples of 64. */
    private static final class ChunkPoints {
        final int count;
        /** x, y, z per cube; x/z in cloud-space global blocks. */
        final double[] xyz;
        /** Global grid cell of each cube (for clipping to a task's cell range). */
        final int[] cells;

        ChunkPoints(int count, double[] xyz, int[] cells) {
            this.count = count;
            this.xyz = xyz;
            this.cells = cells;
        }
    }

    /**
     * Single worker thread. A newer task supersedes queued and running ones. The sample-chunk
     * cache is keyed by the exact global chunk (the grid is aligned to 64-cell multiples, so
     * cached chunks never cover shifted cell ranges) and is a two-generation LRU, rebuilt when
     * the seed or the cloudiness percent changes.
     */
    private static final class Generator {
        final AtomicReference<Result> completed = new AtomicReference<>();
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Vesu Better Clouds");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        private volatile int latest;

        // Worker-thread state.
        private DensityField field;
        private long cacheSeed;
        private int cachePct = -1;
        private HashMap<Long, ChunkPoints> fresh = new HashMap<>();
        private HashMap<Long, ChunkPoints> stale = new HashMap<>();

        void submit(Task task) {
            latest = task.id;
            worker.execute(() -> {
                if (task.id != latest) return;
                try {
                    Result result = run(task);
                    if (result != null) {
                        completed.set(result);
                    }
                } catch (Throwable t) {
                    Initializer.LOGGER.error("[clouds] better clouds generation failed", t);
                }
            });
        }

        void cancelAll() {
            latest = -1;
            completed.set(null);
        }

        private Result run(Task task) {
            if (field == null || cacheSeed != task.seed) {
                field = new DensityField(task.seed);
                cacheSeed = task.seed;
                fresh.clear();
                stale.clear();
            }
            if (cachePct != task.pct) {
                cachePct = task.pct;
                fresh.clear();
                stale.clear();
            }

            int gridMin = -(int) Math.floor(task.distance / SPACING);
            int gridMax = (int) Math.ceil(task.distance / SPACING);
            int gx = (int) Math.floor(task.cx * (double) CHUNK_SIZE / SPACING);
            int gz = (int) Math.floor(task.cz * (double) CHUNK_SIZE / SPACING);
            int x0 = gx + gridMin, x1 = gx + gridMax; // global cells [x0, x1)
            int z0 = gz + gridMin, z1 = gz + gridMax;
            int kx0 = Math.floorDiv(x0, CHUNK_SIZE), kx1 = Math.floorDiv(x1 - 1, CHUNK_SIZE);
            int kz0 = Math.floorDiv(z0, CHUNK_SIZE), kz1 = Math.floorDiv(z1 - 1, CHUNK_SIZE);

            double originX = (double) task.cx * CHUNK_SIZE, originZ = (double) task.cz * CHUNK_SIZE;
            float[] positions = new float[3 * 4096];
            int count = 0;
            List<int[]> chunkRanges = new ArrayList<>();
            List<float[]> chunkBounds = new ArrayList<>();

            for (int kx = kx0; kx <= kx1; kx++) {
                for (int kz = kz0; kz <= kz1; kz++) {
                    if (task.id != latest) return null;
                    long key = (long) kx << 32 | (kz & 0xFFFFFFFFL);
                    ChunkPoints points = fresh.get(key);
                    if (points == null) {
                        points = stale.remove(key);
                        if (points == null) {
                            points = generateChunk(kx, kz, task.cloudiness);
                        }
                        fresh.put(key, points);
                    }
                    int first = count;
                    float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                    for (int i = 0; i < points.count; i++) {
                        int cellX = points.cells[2 * i], cellZ = points.cells[2 * i + 1];
                        if (cellX < x0 || cellX >= x1 || cellZ < z0 || cellZ >= z1) continue;
                        double px = points.xyz[3 * i], py = points.xyz[3 * i + 1], pz = points.xyz[3 * i + 2];
                        if (3 * count + 3 > positions.length) positions = Arrays.copyOf(positions, positions.length * 2);
                        positions[3 * count] = (float) (px - originX);
                        positions[3 * count + 1] = (float) py;
                        positions[3 * count + 2] = (float) (pz - originZ);
                        count++;
                        b[0] = Math.min(b[0], (float) px); b[1] = Math.min(b[1], (float) py); b[2] = Math.min(b[2], (float) pz);
                        b[3] = Math.max(b[3], (float) px); b[4] = Math.max(b[4], (float) py); b[5] = Math.max(b[5], (float) pz);
                    }
                    if (count > first) {
                        chunkRanges.add(new int[]{first, count - first});
                        chunkBounds.add(b);
                    }
                }
            }
            // Two-generation LRU: what this task did not touch is dropped after the next one.
            HashMap<Long, ChunkPoints> swap = stale;
            stale = fresh;
            fresh = swap;
            fresh.clear();

            int chunks = chunkRanges.size();
            int[] firsts = new int[chunks], counts = new int[chunks];
            float[] bounds = new float[6 * chunks];
            for (int i = 0; i < chunks; i++) {
                firsts[i] = chunkRanges.get(i)[0];
                counts[i] = chunkRanges.get(i)[1];
                System.arraycopy(chunkBounds.get(i), 0, bounds, 6 * i, 6);
            }
            return new Result(task.id, task.cx, task.cz, task.distance, count, positions, firsts, counts, bounds);
        }

        /** Grid cells to cube positions (spec 3.3), X outer, Z inner. */
        private ChunkPoints generateChunk(int kx, int kz, float cloudiness) {
            double[] xyz = new double[3 * 1024];
            int[] cells = new int[2 * 1024];
            int n = 0;
            long sd = field.seed;
            for (int gx = kx * CHUNK_SIZE; gx < (kx + 1) * CHUNK_SIZE; gx++) {
                for (int gz = kz * CHUNK_SIZE; gz < (kz + 1) * CHUNK_SIZE; gz++) {
                    if (SPARSITY > 0.0 && hash01(sd, 'G', gx, gz) < SPARSITY) continue;
                    int sx = (int) Math.floor(gx * SPACING), sz = (int) Math.floor(gz * SPACING);
                    double v = field.sample(sx, sz, cloudiness);
                    if (v <= 0.0) continue;
                    for (int pass = 0; pass < 2; pass++) {
                        double h;
                        if (pass == 0) {
                            h = v * v * Y_RANGE;
                        } else {
                            if (v < 1.0 - BOTTOM_SPARSITY) break;
                            h = -0.3 * v * v * Y_RANGE;
                        }
                        if (3 * n + 3 > xyz.length) {
                            xyz = Arrays.copyOf(xyz, xyz.length * 2);
                            cells = Arrays.copyOf(cells, cells.length * 2);
                        }
                        xyz[3 * n] = sx + hash01(sd, 'S', sx, sz, 'X', pass) * RANDOM_PLACEMENT * SPACING;
                        xyz[3 * n + 1] = h;
                        xyz[3 * n + 2] = sz + hash01(sd, 'S', sx, sz, 'Z', pass) * RANDOM_PLACEMENT * SPACING;
                        cells[2 * n] = gx;
                        cells[2 * n + 1] = gz;
                        n++;
                    }
                }
            }
            return new ChunkPoints(n, xyz, cells);
        }
    }

    /** Noise objects of one seed (spec 3.1) and the density sample (spec 3.2). */
    private static final class DensityField {
        final long seed;
        final NoiseGeneratorSimplex region, coverage;
        final NoiseGeneratorSimplex[][] stackNoise;
        final double[][] stackFreq, stackWeight;

        DensityField(long seed) {
            this.seed = seed;
            Random main = new Random(seed);
            Random secondary = new Random(main.nextInt());
            region = new NoiseGeneratorSimplex(secondary);
            coverage = new NoiseGeneratorSimplex(main);
            int sets = OCTAVE_SETS.length;
            stackNoise = new NoiseGeneratorSimplex[sets][];
            stackFreq = new double[sets][];
            stackWeight = new double[sets][];
            for (int s = 0; s < sets; s++) {
                int[] set = OCTAVE_SETS[s];
                int min = Arrays.stream(set).min().getAsInt(), max = Arrays.stream(set).max().getAsInt();
                List<NoiseGeneratorSimplex> noises = new ArrayList<>();
                List<double[]> fw = new ArrayList<>();
                double norm = Math.pow(2.0, max - min + 1) - 1.0;
                for (int o = max; o >= min; o--) {
                    NoiseGeneratorSimplex noise = new NoiseGeneratorSimplex(main);
                    final int oct = o;
                    if (Arrays.stream(set).anyMatch(e -> e == oct)) {
                        noises.add(noise);
                        fw.add(new double[]{Math.pow(2.0, o), Math.pow(2.0, max - o) / norm});
                    }
                }
                stackNoise[s] = noises.toArray(new NoiseGeneratorSimplex[0]);
                stackFreq[s] = fw.stream().mapToDouble(a -> a[0]).toArray();
                stackWeight[s] = fw.stream().mapToDouble(a -> a[1]).toArray();
            }
        }

        double stack(int s, double u, double v) {
            double sum = 0.0;
            NoiseGeneratorSimplex[] noises = stackNoise[s];
            for (int i = 0; i < noises.length; i++) {
                double f = stackFreq[s][i];
                sum += stackWeight[s][i] * noises[i].getValue(u * f, v * f);
            }
            return sum;
        }

        double sample(int x, int z, double c) {
            int sets = stackNoise.length;
            double u = x / (128.0 * SAMPLING_SCALE), w = z / (128.0 * SAMPLING_SCALE);
            double n;
            if (sets > 1) {
                double g = (region.getValue(x / 2048.0, z / 2048.0) * 0.5 + 0.5) * sets;
                int i = MathHelper.floor(g);
                double t = MathHelper.clamp(g - i, 0.0, 1.0);
                t = t * t * t * t * t;
                double a = stack(Math.floorMod(i, sets), u, w);
                double b = stack(Math.floorMod(i + 1, sets), u, w);
                n = a + (b - a) * t;
            } else {
                n = stack(0, u, w);
            }
            double v = n / 2.0 + 0.5;
            v = (v - (1.0 - c)) / c;
            v *= smoothstep(-0.6 * c - 0.5, -0.6 * c, coverage.getValue(x / 1024.0, z / 1024.0));
            if (v <= 0.0) return 0.0;
            double r = hash01(seed, 'B', x, z);
            if (r > v + (0.9 - FUZZINESS)) v = 0.0;
            return v;
        }
    }

    // --- hashing (Jenkins one-at-a-time on 64 bits) ---

    static long hash(long seed, int... values) {
        long h = seed;
        for (int v : values) {
            h += v;
            h += h << 10;
            h ^= h >>> 6;
        }
        h += h << 3;
        h ^= h >> 11;
        h += h << 15;
        return h;
    }

    static float hash01(long seed, int... values) {
        long h = hash(seed, values);
        int folded = (int) (h >>> 32) ^ (int) h;
        return Float.intBitsToFloat(0x3F800000 | (folded & 0x7FFFFF)) - 1.0f;
    }

    // --- math ---

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = MathHelper.clamp((x - e0) / (e1 - e0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static float remap(float x, float a0, float a1, float b0, float b1) {
        if (a1 == a0) return b0;
        return b0 + (x - a0) * (b1 - b0) / (a1 - a0);
    }
}
