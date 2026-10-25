#version 450

// Cloud fragments: the scrolling clouds.png modulated by the per-vertex cloud
// colour (0.8 alpha) and ColorModulator, then blended toward FogColor with the
// same fixed-function fog as the terrain/sky. No alpha discard: the SRC_ALPHA /
// ONE_MINUS_SRC_ALPHA blend already lets fully transparent cloud texels show the
// sky behind them, matching the GL path.

layout(binding = 1) uniform UniformBufferObject {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * vertexColor * texture(Sampler0, texCoord0);

    // GL fog replaces only the fragment RGB; the alpha (the blend
    // weight of translucent draws) passes through untouched.
    if (FogMode == 1) {
        float f = clamp((FogEnd - vertexDistance) / (FogEnd - FogStart), 0.0, 1.0);
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
