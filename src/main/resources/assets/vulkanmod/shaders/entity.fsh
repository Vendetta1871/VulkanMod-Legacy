#version 450

// Entity fragments: the entity skin modulated by the entity tint (ColorModulator) and
// the two-light directional shading computed per vertex, with the live fixed-function
// alpha test (AlphaRef) and the Cleanroom hurt/death red overlay (RedOverlay), plus the
// same fog as the terrain.
//
// The lightmap is still missing: vanilla multiplies a single constant lightmap sample
// (the entity's block/sky light) into the result. In daylight that sample saturates to
// white, so the omission is invisible there; dark areas need it layered on as
// Sampler2, like the terrain shader does.

layout(binding = 1) uniform UniformBufferObject {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
    float AlphaRef;
    float RedOverlay;
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float lightAccum;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * texture(Sampler0, texCoord0);

    // Fixed-function alpha test: AlphaRef carries the live alphaFunc reference (0.1 for
    // the model pass, TRANSPARENT_MODEL's 1/255 for see-through mobs, 0 when the test is
    // disabled - e.g. the additive eye pass, where the dark texels must not be discarded).
    if (color.a < AlphaRef) {
        discard;
    }

    color.rgb *= lightAccum;

    // Cleanroom's hurt/death overlay: a 30% red mix over the shaded model, before fog.
    color.rgb = mix(color.rgb, vec3(1.0, 0.0, 0.0), RedOverlay);

    // GL fog replaces only the fragment RGB; the alpha (the blend
    // weight of translucent draws) passes through untouched.
    if (FogMode == 1) {
        float f = clamp((FogEnd - vertexDistance) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
        color.rgb = mix(FogColor.rgb, color.rgb, f);
    }
    else if (FogMode == 2) {
        float f = clamp(exp(-FogDensity * vertexDistance), 0.0, 1.0);
        color.rgb = mix(FogColor.rgb, color.rgb, f);
    }
    else if (FogMode == 3) {
        float d = FogDensity * vertexDistance;
        float f = clamp(exp(-d * d), 0.0, 1.0);
        color.rgb = mix(FogColor.rgb, color.rgb, f);
    }

    fragColor = color;
}
