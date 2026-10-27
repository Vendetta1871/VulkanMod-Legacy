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
    float AlphaFunc;
    float RedOverlay;
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float lightAccum;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * texture(Sampler0, texCoord0);

    // GL fixed-function alpha test with the live func/ref: keep the fragment only
    // when compare(color.a, AlphaRef) holds. AlphaFunc carries the GL compare enum
    // (NEVER 512 ... ALWAYS 519); 0 means the test is disabled (M21, N23).
    int alphaFunc = int(AlphaFunc);
    if (alphaFunc != 0) {
        bool keep;
        if (alphaFunc == 512) keep = false;                                   // NEVER
        else if (alphaFunc == 513) keep = color.a < AlphaRef;                 // LESS
        else if (alphaFunc == 514) keep = abs(color.a - AlphaRef) < 0.0039;   // EQUAL
        else if (alphaFunc == 515) keep = color.a <= AlphaRef;                // LEQUAL
        else if (alphaFunc == 516) keep = color.a > AlphaRef;                 // GREATER
        else if (alphaFunc == 517) keep = abs(color.a - AlphaRef) >= 0.0039;  // NOTEQUAL
        else if (alphaFunc == 518) keep = color.a >= AlphaRef;                // GEQUAL
        else keep = true;                                                      // ALWAYS
        if (!keep) {
            discard;
        }
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
