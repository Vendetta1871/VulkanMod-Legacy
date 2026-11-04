package net.vesu.shader;

import net.vesu.Env;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.vesu.Initializer;
import net.vesu.pack.host.FrameBlock;
import net.vesu.pack.host.Std140Layout;
import net.vesu.render.CameraHistory;
import net.vesu.vulkan.FrameTargets;
import net.vesu.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Fills the per-frame host block ({@link FrameBlock}, host-interface.md) from the game
 * state, once per frame. Only runs while a shader pack is selected (or with
 * {@code VESU_FRAME_DUMP=1}, which also logs the values every 120 frames), so the
 * no-pack frame pays nothing (goal 4).
 *
 * <p>The fill runs once per frame at the head of the terrain pass, right after the camera
 * capture, where camera and fog are the world's; {@code PackRuntime} uploads the result
 * before the pack passes (and fills it there itself if no terrain pass ran). Members whose
 * source arrives later stay at their defaults: shadow matrices (identity, M4), atlas sizes
 * and dynamic lights (0, M3); {@code PackRuntime} overwrites {@code centerDepthSmooth}. Values marked "(verify)" follow OptiFine as far as known.</p>
 */
public final class FrameUniforms {

    private static final boolean DUMP = Env.get("VESU_FRAME_DUMP") != null;

    private static final Std140Layout L = FrameBlock.LAYOUT;
    private static final ByteBuffer DATA = MemoryUtil.memCalloc(L.size());

    // Offsets resolved once: the per-frame fill does no name lookups.
    private static final int MODEL_VIEW = L.offset("gbufferModelView"),
            MODEL_VIEW_INV = L.offset("gbufferModelViewInverse"),
            PROJECTION = L.offset("gbufferProjection"),
            PROJECTION_INV = L.offset("gbufferProjectionInverse"),
            PREV_MODEL_VIEW = L.offset("gbufferPreviousModelView"),
            PREV_PROJECTION = L.offset("gbufferPreviousProjection"),
            SHADOW_MV = L.offset("shadowModelView"), SHADOW_MV_INV = L.offset("shadowModelViewInverse"),
            SHADOW_P = L.offset("shadowProjection"), SHADOW_P_INV = L.offset("shadowProjectionInverse"),
            CAMERA = L.offset("cameraPosition"), EYE_ALTITUDE = L.offset("eyeAltitude"),
            CAMERA_INT = L.offset("cameraPositionInt"), CAMERA_FRACT = L.offset("cameraPositionFract"),
            PREV_CAMERA_INT = L.offset("previousCameraPositionInt"), PREV_CAMERA_FRACT = L.offset("previousCameraPositionFract"),
            EYE_POSITION = L.offset("eyePosition"), RELATIVE_EYE = L.offset("relativeEyePosition"),
            LOOK_VECTOR = L.offset("playerLookVector"), LIGHTNING = L.offset("lightningBoltPosition"),
            PREV_CAMERA = L.offset("previousCameraPosition"), NEAR = L.offset("near"),
            SUN = L.offset("sunPosition"), FAR = L.offset("far"),
            MOON = L.offset("moonPosition"), ASPECT = L.offset("aspectRatio"),
            UP = L.offset("upPosition"), VIEW_WIDTH = L.offset("viewWidth"),
            SHADOW_LIGHT = L.offset("shadowLightPosition"), VIEW_HEIGHT = L.offset("viewHeight"),
            SKY_COLOR = L.offset("skyColor"), SUN_ANGLE = L.offset("sunAngle"),
            FOG_COLOR = L.offset("fogColor"), SHADOW_ANGLE = L.offset("shadowAngle"),
            FOG_DENSITY = L.offset("fogDensity"), FOG_START = L.offset("fogStart"),
            FOG_END = L.offset("fogEnd"), FOG_MODE = L.offset("fogMode"),
            WORLD_TIME = L.offset("worldTime"), WORLD_DAY = L.offset("worldDay"),
            MOON_PHASE = L.offset("moonPhase"), FRAME_COUNTER = L.offset("frameCounter"),
            FRAME_TIME = L.offset("frameTime"), FRAME_TIME_COUNTER = L.offset("frameTimeCounter"),
            RAIN = L.offset("rainStrength"), WETNESS = L.offset("wetness"),
            EYE_IN_WATER = L.offset("isEyeInWater"), NIGHT_VISION = L.offset("nightVision"),
            BLINDNESS = L.offset("blindness"), SCREEN_BRIGHTNESS = L.offset("screenBrightness"),
            EYE_BRIGHTNESS = L.offset("eyeBrightness"), EYE_BRIGHTNESS_SMOOTH = L.offset("eyeBrightnessSmooth"),
            HELD_ITEM = L.offset("heldItemId"), HELD_LIGHT = L.offset("heldBlockLightValue"),
            HELD_ITEM2 = L.offset("heldItemId2"), HELD_LIGHT2 = L.offset("heldBlockLightValue2"),
            HIDE_GUI = L.offset("hideGUI"), DIMENSION = L.offset("vkm_dimension"), BIOME = L.offset("vkm_biome"),
            JITTER = L.offset("vkm_jitter"), DISPLAY_SIZE = L.offset("vkm_displaySize"),
            RENDER_SCALE = L.offset("vkm_renderScale"), RT_ENABLED = L.offset("vkm_rtEnabled"),
            ATLAS_SIZE = L.offset("atlasSize"), TERRAIN_SIZE = L.offset("terrainTextureSize"), ICON_SIZE = L.offset("terrainIconSize"),
            DH_P = L.offset("dhProjection"), DH_P_INV = L.offset("dhProjectionInverse"), DH_PREV_P = L.offset("dhPreviousProjection"),
            DH_NEAR = L.offset("dhNearPlane"), DH_FAR = L.offset("dhFarPlane"), DH_DISTANCE = L.offset("dhRenderDistance"),
            HEIGHT_LIMIT = L.offset("heightLimit"), LOGICAL_HEIGHT = L.offset("logicalHeightLimit"), BEDROCK = L.offset("bedrockLevel");

    /**
     * {@code far}: the vanilla distance (AUDIT 5.16). A pack without DH programs fogs and fades
     * at {@code far}, which would hide the LODs drawn through its terrain programs: then it
     * gets the LOD distance instead.
     */
    static float farUniform(net.minecraft.client.Minecraft mc) {
        float vanilla = mc.gameSettings.renderDistanceChunks * 16.0f;
        float lod = net.vesu.vulkan.VRenderSystem.lodFarPlane - 512.0f;
        if (lod <= vanilla || !net.vesu.vulkan.LodPackBridge.throughTerrainPrograms()) return vanilla;
        return lod;
    }

    /** Near plane of the distant terrain's own projection (AUDIT 5.16, contract B), in blocks. */
    public static final float DH_NEAR_PLANE = 16.0f;

    /** Far plane of the distant terrain's projection: the LOD distance plus one top-level section. */
    public static float dhFarPlane() {
        return net.vesu.Initializer.CONFIG.lodDistance * 16.0f + 512.0f;
    }

    /** {@code gl}, a GL perspective matrix, with other near and far planes. */
    public static Matrix4f withPlanes(Matrix4f gl, float n, float f, Matrix4f dest) {
        dest.set(gl);
        dest.m22(-(f + n) / (f - n));
        dest.m32(-2.0f * f * n / (f - n));
        return dest;
    }

    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Matrix4f view = new Matrix4f(), proj = new Matrix4f(), tmp = new Matrix4f(),
            celestial = new Matrix4f();
    private static final Vector4f vec = new Vector4f();

    private static int frameCounter;
    private static long lastNanos;
    private static float frameTimeCounter;
    private static float wetness;
    private static float eyeBlockSmooth = -1, eyeSkySmooth = -1;
    private static boolean filledOnce;

    /** Sun path tilt in degrees: the pack's {@code shadow.sunPathRotation}. */
    private static float sunPathRotation;

    public static float sunPathRotation() {
        return PackManager.active() != null ? sunPathRotation : 0.0f;
    }

    private static final Matrix4f shadowView = new Matrix4f(), shadowProjection = new Matrix4f(),
            shadowProjectionRemapped = new Matrix4f(), viewInverse = new Matrix4f();
    private static double cameraX, cameraY, cameraZ;

    /** The light's model-view; applied to camera-relative world positions. */
    public static Matrix4f shadowView() {
        return shadowView;
    }

    /** The light's projection with the Vulkan depth remap, for the host clip transform. */
    public static Matrix4f shadowProjectionRemapped() {
        return shadowProjectionRemapped;
    }

    public static Matrix4f cameraViewInverse() {
        return viewInverse;
    }

    public static double cameraX() {
        return cameraX;
    }

    public static double cameraY() {
        return cameraY;
    }

    public static double cameraZ() {
        return cameraZ;
    }

    private FrameUniforms() {
    }

    /** The filled block (std140), valid after the first {@link #onWorldRendered}. */
    public static ByteBuffer data() {
        return DATA;
    }

    private static boolean filledThisFrame;

    /** Called at the start of every render frame. */
    public static void beginFrame() {
        filledThisFrame = false;
    }

    /** Called once per frame after the camera capture at the head of the terrain pass. */
    public static void onTerrainBegin() {
        if (PackManager.active() == null && !DUMP) {
            return;
        }
        ensureFilled();
    }

    /** Fills the block unless this frame already did (the pack runtime calls it before uploading). */
    public static void ensureFilled() {
        if (filledThisFrame) {
            return;
        }
        filledThisFrame = true;
        try {
            if (fill(Minecraft.getMinecraft().getRenderPartialTicks()) && DUMP && frameCounter % 120 == 0) {
                dump();
            }
        } catch (RuntimeException e) {
            // A bad value must never take the frame down; log once.
            if (!filledOnce) {
                Initializer.LOGGER.error("[frame] filling the frame block failed", e);
                filledOnce = true;
            }
        }
    }

    /** Called at the end of {@code EntityRenderer.renderWorld}: frames without a terrain pass. */
    public static void onWorldRendered(float partialTicks) {
        if (DUMP) {
            ensureFilled();
        }
    }

    private static boolean fill(float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        Entity viewer = mc.getRenderViewEntity();
        if (world == null || viewer == null) {
            return false;
        }
        ByteBuffer b = DATA;

        long now = System.nanoTime();
        float frameTime = lastNanos == 0 ? 0.0f : (now - lastNanos) * 1e-9f;
        lastNanos = now;
        frameTimeCounter = (frameTimeCounter + frameTime) % 3600.0f;
        frameCounter = (frameCounter + 1) % 720720;

        // --- Camera: GL matrices, i.e. CameraHistory's with the Vulkan depth remap undone.
        if (CameraHistory.valid()) {
            view.set(CameraHistory.view());
            undoDepthRemap(CameraHistory.proj(), proj);
        }
        view.get(MODEL_VIEW, b);
        view.invert(tmp).get(MODEL_VIEW_INV, b);
        proj.get(PROJECTION, b);
        proj.invert(tmp).get(PROJECTION_INV, b);
        if (CameraHistory.hasPrevious()) {
            CameraHistory.prevView().get(PREV_MODEL_VIEW, b);
            undoDepthRemap(CameraHistory.prevProj(), tmp).get(PREV_PROJECTION, b);
        } else {
            view.get(PREV_MODEL_VIEW, b);
            proj.get(PREV_PROJECTION, b);
        }
        float dhFar = dhFarPlane();
        withPlanes(proj, DH_NEAR_PLANE, dhFar, tmp).get(DH_P, b);
        tmp.invert().get(DH_P_INV, b);
        if (CameraHistory.hasPrevious()) withPlanes(undoDepthRemap(CameraHistory.prevProj(), tmp), DH_NEAR_PLANE, dhFar, tmp);
        else withPlanes(proj, DH_NEAR_PLANE, dhFar, tmp);
        tmp.get(DH_PREV_P, b);
        b.putFloat(DH_NEAR, DH_NEAR_PLANE);
        b.putFloat(DH_FAR, dhFar);
        b.putInt(DH_DISTANCE, net.vesu.Initializer.CONFIG.lodDistance * 16);
        b.putInt(HEIGHT_LIMIT, 256);
        b.putInt(LOGICAL_HEIGHT, 256);
        b.putInt(BEDROCK, 0);
        double camX = CameraHistory.camX(), camY = CameraHistory.camY(), camZ = CameraHistory.camZ();
        cameraX = camX;
        cameraY = camY;
        cameraZ = camZ;
        view.invert(viewInverse);
        net.vesu.pack.ir.IrPack.Shadow shadow = PackManager.active() != null ? PackManager.active().shadow : null;
        sunPathRotation = shadow != null ? shadow.sunPathRotation : 0.0f;
        if (shadow != null) {
            fillShadow(shadow, world.getCelestialAngle(pt), camX, camY, camZ);
            shadowView.get(SHADOW_MV, b);
            shadowView.invert(tmp).get(SHADOW_MV_INV, b);
            shadowProjection.get(SHADOW_P, b);
            shadowProjection.invert(tmp).get(SHADOW_P_INV, b);
        } else {
            IDENTITY.get(SHADOW_MV, b);
            IDENTITY.get(SHADOW_MV_INV, b);
            IDENTITY.get(SHADOW_P, b);
            IDENTITY.get(SHADOW_P_INV, b);
        }

        putVec3(b, CAMERA, (float) camX, (float) camY, (float) camZ);
        b.putFloat(EYE_ALTITUDE, (float) (camY + viewer.getEyeHeight()));
        if (CameraHistory.hasPrevious()) {
            putVec3(b, PREV_CAMERA, (float) CameraHistory.prevCamX(), (float) CameraHistory.prevCamY(),
                    (float) CameraHistory.prevCamZ());
        } else {
            putVec3(b, PREV_CAMERA, (float) camX, (float) camY, (float) camZ);
        }
        putSplit(b, CAMERA_INT, CAMERA_FRACT, camX, camY, camZ);
        if (CameraHistory.hasPrevious()) {
            putSplit(b, PREV_CAMERA_INT, PREV_CAMERA_FRACT, CameraHistory.prevCamX(), CameraHistory.prevCamY(), CameraHistory.prevCamZ());
        } else {
            putSplit(b, PREV_CAMERA_INT, PREV_CAMERA_FRACT, camX, camY, camZ);
        }
        Vec3d eye = viewer.getPositionEyes(pt), look = viewer.getLook(pt);
        putVec3(b, EYE_POSITION, (float) eye.x, (float) eye.y, (float) eye.z);
        putVec3(b, RELATIVE_EYE, (float) (camX - eye.x), (float) (camY - eye.y), (float) (camZ - eye.z));
        putVec3(b, LOOK_VECTOR, (float) look.x, (float) look.y, (float) look.z);
        putVec3(b, LIGHTNING, 0.0f, 0.0f, 0.0f);
        b.putFloat(LIGHTNING + 12, 0.0f);
        for (Entity e : world.weatherEffects) {
            if (!(e instanceof net.minecraft.entity.effect.EntityLightningBolt)) continue;
            putVec3(b, LIGHTNING, (float) (e.posX - camX), (float) (e.posY - camY), (float) (e.posZ - camZ));
            b.putFloat(LIGHTNING + 12, 1.0f);
            break;
        }
        b.putFloat(NEAR, CameraHistory.near());
        b.putFloat(FAR, farUniform(mc));
        int w = FrameTargets.scene.width(), h = FrameTargets.scene.height();
        b.putFloat(VIEW_WIDTH, w);
        b.putFloat(VIEW_HEIGHT, h);
        b.putFloat(ASPECT, h > 0 ? (float) w / h : 1.0f);

        // --- Sky. Celestial positions as OptiFine derives them: the camera rotation, then
        // the sun path (yaw -90, tilt sunPathRotation, celestial angle) (verify).
        float angle = world.getCelestialAngle(pt);
        float sunAngle = angle < 0.75f ? angle + 0.25f : angle - 0.75f;
        float shadowAngle = sunAngle <= 0.5f ? sunAngle : sunAngle - 0.5f;
        celestial.set(view).rotateY((float) Math.toRadians(-90.0))
                .rotateZ((float) Math.toRadians(sunPathRotation))
                .rotateX((float) Math.toRadians(angle * 360.0));
        celestial.transform(vec.set(0, 100, 0, 0));
        float sx = vec.x, sy = vec.y, sz = vec.z;
        putVec3(b, SUN, sx, sy, sz);
        putVec3(b, MOON, -sx, -sy, -sz);
        if (sunAngle <= 0.5f) putVec3(b, SHADOW_LIGHT, sx, sy, sz);
        else putVec3(b, SHADOW_LIGHT, -sx, -sy, -sz);
        view.transform(vec.set(0, 100, 0, 0));
        putVec3(b, UP, vec.x, vec.y, vec.z);
        b.putFloat(SUN_ANGLE, sunAngle);
        b.putFloat(SHADOW_ANGLE, shadowAngle);
        Vec3d sky = world.getSkyColor(viewer, pt);
        putVec3(b, SKY_COLOR, (float) sky.x, (float) sky.y, (float) sky.z);
        float[] fog = VRenderSystem.getShaderFogColor();
        putVec3(b, FOG_COLOR, fog[0], fog[1], fog[2]);
        b.putFloat(FOG_DENSITY, VRenderSystem.getFogDensity());
        b.putFloat(FOG_START, VRenderSystem.getFogStart());
        b.putFloat(FOG_END, VRenderSystem.getFogEnd());
        b.putInt(FOG_MODE, glFogMode());

        long time = world.getWorldTime();
        b.putInt(WORLD_TIME, (int) (time % 24000L));
        b.putInt(WORLD_DAY, (int) (time / 24000L));
        b.putInt(MOON_PHASE, world.getMoonPhase());
        b.putInt(FRAME_COUNTER, frameCounter);
        b.putFloat(FRAME_TIME, frameTime);
        b.putFloat(FRAME_TIME_COUNTER, frameTimeCounter);

        // --- Weather. wetness follows rain with OptiFine's default half-lives,
        // wetnessHalflife 600 / drynessHalflife 200, taken as ticks here (verify the unit).
        float rain = world.getRainStrength(pt);
        b.putFloat(RAIN, rain);
        wetness = smooth(wetness, rain, rain > wetness ? 200.0f / 20.0f : 600.0f / 20.0f, frameTime);
        b.putFloat(WETNESS, wetness);

        // --- Player.
        int inWater = viewer.isInsideOfMaterial(Material.WATER) ? 1 : viewer.isInsideOfMaterial(Material.LAVA) ? 2 : 0;
        b.putInt(EYE_IN_WATER, inWater);
        b.putFloat(NIGHT_VISION, effectStrength(viewer, MobEffects.NIGHT_VISION, pt, true));
        b.putFloat(BLINDNESS, effectStrength(viewer, MobEffects.BLINDNESS, pt, false));
        b.putFloat(SCREEN_BRIGHTNESS, mc.gameSettings.gammaSetting);
        int light = world.getCombinedLight(new BlockPos(camX, camY + viewer.getEyeHeight(), camZ), 0);
        int eyeBlock = light & 0xFFFF, eyeSky = (light >>> 16) & 0xFFFF;
        b.putInt(EYE_BRIGHTNESS, eyeBlock);
        b.putInt(EYE_BRIGHTNESS + 4, eyeSky);
        // eyeBrightnessHalflife 10, taken as ticks (verify the unit).
        eyeBlockSmooth = eyeBlockSmooth < 0 ? eyeBlock : smooth(eyeBlockSmooth, eyeBlock, 0.5f, frameTime);
        eyeSkySmooth = eyeSkySmooth < 0 ? eyeSky : smooth(eyeSkySmooth, eyeSky, 0.5f, frameTime);
        b.putInt(EYE_BRIGHTNESS_SMOOTH, Math.round(eyeBlockSmooth));
        b.putInt(EYE_BRIGHTNESS_SMOOTH + 4, Math.round(eyeSkySmooth));
        if (viewer instanceof EntityLivingBase living) {
            putHeld(b, HELD_ITEM, HELD_LIGHT, living.getHeldItemMainhand());
            putHeld(b, HELD_ITEM2, HELD_LIGHT2, living.getHeldItemOffhand());
        } else {
            putHeld(b, HELD_ITEM, HELD_LIGHT, ItemStack.EMPTY);
            putHeld(b, HELD_ITEM2, HELD_LIGHT2, ItemStack.EMPTY);
        }
        if (PackManager.rendererIsFalse("dynamicHandLight")) {
            b.putInt(HELD_LIGHT, 0);
            b.putInt(HELD_LIGHT2, 0);
        } else if (!PackManager.rendererIsFalse("oldHandLight") && b.getInt(HELD_LIGHT2) > b.getInt(HELD_LIGHT)) {
            // The brighter hand lights the scene, as with a single held item.
            b.putInt(HELD_ITEM, b.getInt(HELD_ITEM2));
            b.putInt(HELD_LIGHT, b.getInt(HELD_LIGHT2));
        }
        b.putInt(HIDE_GUI, mc.gameSettings.hideGUI ? 1 : 0);

        net.vesu.vulkan.texture.VulkanImage atlas = net.vesu.render.TextureBridge.getAtlasImage();
        if (atlas != null) {
            b.putInt(ATLAS_SIZE, atlas.getWidth());
            b.putInt(ATLAS_SIZE + 4, atlas.getHeight());
            b.putInt(TERRAIN_SIZE, atlas.getWidth());
            b.putInt(TERRAIN_SIZE + 4, atlas.getHeight());
            b.putInt(ICON_SIZE, 16);
        }

        // --- Host extras.
        b.putInt(DIMENSION, world.provider.getDimension());
        b.putInt(BIOME, net.minecraft.world.biome.Biome.getIdForBiome(world.getBiome(mc.getRenderViewEntity().getPosition())));
        b.putFloat(JITTER, CameraHistory.jitterX());
        b.putFloat(JITTER + 4, CameraHistory.jitterY());
        b.putFloat(DISPLAY_SIZE, FrameTargets.display.width());
        b.putFloat(DISPLAY_SIZE + 4, FrameTargets.display.height());
        b.putFloat(RENDER_SCALE, FrameTargets.renderScale());
        b.putInt(RT_ENABLED, net.vesu.vulkan.rt.RtScene.enabled() ? 1 : 0);
        filledOnce = true;
        return true;
    }

    /**
     * The light's matrices as OptiFine G5 builds them ({@code Shaders.setCameraShadow}): the eye
     * 100 blocks from the camera, the sun's rotation, and a translation that snaps the camera
     * to {@code intervalSize}, so the texels of the map stay put in the world.
     */
    private static void fillShadow(net.vesu.pack.ir.IrPack.Shadow shadow, float celestial, double x, double y, double z) {
        float half = shadow.distance;
        if (shadow.fov > 0.0f) {
            shadowProjection.setPerspective((float) Math.toRadians(shadow.fov), 1.0f, 0.05f, 256.0f);
        } else {
            shadowProjection.setOrtho(-half, half, -half, half, 0.05f, 256.0f);
        }
        float sun = celestial < 0.75f ? celestial + 0.25f : celestial - 0.75f;
        float angle = celestial * -360.0f;
        float snap = shadow.angleInterval > 0.0f ? angle % shadow.angleInterval - shadow.angleInterval * 0.5f : 0.0f;
        shadowView.identity().translate(0.0f, 0.0f, -100.0f).rotateX((float) Math.toRadians(90.0))
                .rotateZ((float) Math.toRadians(sun <= 0.5f ? angle - snap : angle + 180.0f - snap))
                .rotateX((float) Math.toRadians(shadow.sunPathRotation));
        if (shadow.fov <= 0.0f) {
            float t = shadow.intervalSize, h = t * 0.5f;
            shadowView.translate((float) x % t - h, (float) y % t - h, (float) z % t - h);
        }
        // The host clip transform: z' = 0.5 z + 0.5 w, as VRenderSystem.DEPTH_REMAP.
        shadowProjectionRemapped.set(shadowProjection);
        shadowProjectionRemapped.m02(0.5f * shadowProjection.m02() + 0.5f * shadowProjection.m03());
        shadowProjectionRemapped.m12(0.5f * shadowProjection.m12() + 0.5f * shadowProjection.m13());
        shadowProjectionRemapped.m22(0.5f * shadowProjection.m22() + 0.5f * shadowProjection.m23());
        shadowProjectionRemapped.m32(0.5f * shadowProjection.m32() + 0.5f * shadowProjection.m33());
    }

    /** Inverse of the host's depth remap (VRenderSystem.remapDepth). */
    static Matrix4f undoDepthRemap(Matrix4f remapped, Matrix4f dest) {
        return net.vesu.vulkan.VRenderSystem.undoDepthRemap(remapped, dest);
    }

    /** Exponential approach to {@code target} with the given half-life in seconds. */
    private static float smooth(float current, float target, float halfLifeSeconds, float dt) {
        if (dt <= 0.0f) return current;
        float k = (float) Math.pow(0.5, dt / halfLifeSeconds);
        return target + (current - target) * k;
    }

    /**
     * Strength of a status effect, 0..1. Night vision flickers out like vanilla's
     * {@code EntityRenderer.getNightVisionBrightness}; blindness fades over its last second.
     */
    private static float effectStrength(Entity e, Potion potion, float pt, boolean nightVision) {
        if (!(e instanceof EntityLivingBase living) || !living.isPotionActive(potion)) {
            return 0.0f;
        }
        PotionEffect effect = living.getActivePotionEffect(potion);
        if (effect == null) return 0.0f;
        int duration = effect.getDuration();
        if (nightVision) {
            return duration > 200 ? 1.0f : 0.7f + (float) Math.sin((duration - pt) * Math.PI * 0.2) * 0.3f;
        }
        return Math.min(1.0f, duration / 20.0f);
    }

    private static void putHeld(ByteBuffer b, int idOffset, int lightOffset, ItemStack stack) {
        if (stack.isEmpty()) {
            b.putInt(idOffset, -1);
            b.putInt(lightOffset, 0);
            return;
        }
        Item item = stack.getItem();
        b.putInt(idOffset, PackIds.itemId(item));
        b.putInt(lightOffset, item instanceof ItemBlock block ? block.getBlock().getDefaultState().getLightValue() : 0);
    }

    /** OptiFine's fogMode is the GL enum: GL_LINEAR, GL_EXP, GL_EXP2, or 0 without fog. */
    private static int glFogMode() {
        if (!VRenderSystem.isFogEnabled()) return 0;
        return switch (VRenderSystem.getFogState()) {
            case VRenderSystem.FOG_LINEAR -> 0x2601;
            case VRenderSystem.FOG_EXP -> 0x0800;
            case VRenderSystem.FOG_EXP2 -> 0x0801;
            default -> 0;
        };
    }

    /** A position as whole blocks and the part of a block, each exact where one float vector is not. */
    private static void putSplit(ByteBuffer b, int whole, int part, double x, double y, double z) {
        double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
        b.putInt(whole, (int) fx).putInt(whole + 4, (int) fy).putInt(whole + 8, (int) fz);
        putVec3(b, part, (float) (x - fx), (float) (y - fy), (float) (z - fz));
    }

    private static void putVec3(ByteBuffer b, int offset, float x, float y, float z) {
        b.putFloat(offset, x);
        b.putFloat(offset + 4, y);
        b.putFloat(offset + 8, z);
    }

    private static void dump() {
        ByteBuffer b = DATA;
        Initializer.LOGGER.info(String.format(
                "[frame] #%d time=%d day=%d sunAngle=%.3f sun=(%.1f %.1f %.1f) up=(%.1f %.1f %.1f) cam=(%.2f %.2f %.2f) "
                        + "view=%.0fx%.0f near=%.3f far=%.0f rain=%.2f wet=%.2f eye=%d/%d water=%d fog=%d held=%d dim=%d "
                        + "P[1][1]=%.3f P[2][2]=%.4f P[3][2]=%.4f",
                b.getInt(FRAME_COUNTER), b.getInt(WORLD_TIME), b.getInt(WORLD_DAY), b.getFloat(SUN_ANGLE),
                b.getFloat(SUN), b.getFloat(SUN + 4), b.getFloat(SUN + 8),
                b.getFloat(UP), b.getFloat(UP + 4), b.getFloat(UP + 8),
                b.getFloat(CAMERA), b.getFloat(CAMERA + 4), b.getFloat(CAMERA + 8),
                b.getFloat(VIEW_WIDTH), b.getFloat(VIEW_HEIGHT), b.getFloat(NEAR), b.getFloat(FAR),
                b.getFloat(RAIN), b.getFloat(WETNESS), b.getInt(EYE_BRIGHTNESS), b.getInt(EYE_BRIGHTNESS + 4),
                b.getInt(EYE_IN_WATER), b.getInt(FOG_MODE), b.getInt(HELD_ITEM), b.getInt(DIMENSION),
                b.getFloat(PROJECTION + 20), b.getFloat(PROJECTION + 40), b.getFloat(PROJECTION + 56)));
    }
}
