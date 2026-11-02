package net.vesu.pack.host;

import static net.vesu.pack.host.Std140Layout.Type.*;

/**
 * The per-frame host block (host-interface.md, "Frame block"): written once per frame,
 * visible to every pack program. Member names are OptiFine's, so translated packs need no
 * renaming; host-only members carry the {@code vkm_} prefix.
 *
 * <p>Order matters only for padding: a vec3 is followed by a scalar where possible, so the
 * scalar fills the vec3's fourth slot.</p>
 */
public final class FrameBlock {

    public static final String BLOCK_NAME = "vkm_FrameBlock";
    public static final int PACK_UNIFORM_SLOTS = 128;

    public static final Std140Layout LAYOUT = Std140Layout.builder(BLOCK_NAME)
            // Camera: GL matrices as vanilla set them (unjittered, GL depth).
            .add("gbufferModelView", MAT4)
            .add("gbufferModelViewInverse", MAT4)
            .add("gbufferProjection", MAT4)
            .add("gbufferProjectionInverse", MAT4)
            .add("gbufferPreviousModelView", MAT4)
            .add("gbufferPreviousProjection", MAT4)
            // Shadow (filled from M4 on; identity until then).
            .add("shadowModelView", MAT4)
            .add("shadowModelViewInverse", MAT4)
            .add("shadowProjection", MAT4)
            .add("shadowProjectionInverse", MAT4)
            .add("cameraPosition", VEC3).add("eyeAltitude", FLOAT)
            .add("previousCameraPosition", VEC3).add("near", FLOAT)
            .add("sunPosition", VEC3).add("far", FLOAT)
            .add("moonPosition", VEC3).add("aspectRatio", FLOAT)
            .add("upPosition", VEC3).add("viewWidth", FLOAT)
            .add("shadowLightPosition", VEC3).add("viewHeight", FLOAT)
            .add("skyColor", VEC3).add("sunAngle", FLOAT)
            .add("fogColor", VEC3).add("shadowAngle", FLOAT)
            .add("fogDensity", FLOAT)
            .add("fogStart", FLOAT)
            .add("fogEnd", FLOAT)
            .add("fogMode", INT)
            .add("worldTime", INT)
            .add("worldDay", INT)
            .add("moonPhase", INT)
            .add("frameCounter", INT)
            .add("frameTime", FLOAT)
            .add("frameTimeCounter", FLOAT)
            .add("rainStrength", FLOAT)
            .add("wetness", FLOAT)
            .add("isEyeInWater", INT)
            .add("nightVision", FLOAT)
            .add("blindness", FLOAT)
            .add("screenBrightness", FLOAT)
            .add("eyeBrightness", IVEC2)
            .add("eyeBrightnessSmooth", IVEC2)
            .add("heldItemId", INT)
            .add("heldBlockLightValue", INT)
            .add("heldItemId2", INT)
            .add("heldBlockLightValue2", INT)
            .add("hideGUI", INT)
            .add("centerDepthSmooth", FLOAT)
            .add("terrainIconSize", INT)
            .add("vkm_dimension", INT)
            .add("vkm_biome", INT)
            .add("atlasSize", IVEC2)
            .add("terrainTextureSize", IVEC2)
            .add("vkm_jitter", VEC2)
            .add("vkm_displaySize", VEC2)
            .add("vkm_renderScale", FLOAT)
            .add("vkm_rtEnabled", INT)
            .add("cameraPositionInt", IVEC3)
            .add("cameraPositionFract", VEC3)
            .add("previousCameraPositionInt", IVEC3)
            .add("previousCameraPositionFract", VEC3)
            .add("eyePosition", VEC3)
            .add("relativeEyePosition", VEC3)
            .add("playerLookVector", VEC3)
            .add("lightningBoltPosition", VEC4)
            // Dynamic lights as the terrain shaders get them (filled from M3 on).
            .add("vkm_dynLights", VEC4, 16)
            // Custom uniforms of the pack, one slot each (pack-format.md).
            .add("vkm_packUniforms", VEC4, PACK_UNIFORM_SLOTS)
            .build();

    private FrameBlock() {
    }
}
