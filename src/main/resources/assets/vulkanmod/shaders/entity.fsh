#version 450

// Entity fragments: the entity skin modulated by the entity tint (ColorModulator) and
// the two-light directional shading computed per vertex, with the fixed-function alpha
// test (GREATER 0.1) of the entity cutout and the same fog as the terrain.
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
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float lightAccum;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * texture(Sampler0, texCoord0);

    if (color.a < 0.1) {
        discard;
    }

    color.rgb *= lightAccum;

    if (FogMode == 1) {
        float f = clamp((FogEnd - vertexDistance) / (FogEnd - FogStart), 0.0, 1.0);
        color = mix(FogColor, color, f);
    }
    else if (FogMode == 2) {
        float f = clamp(exp(-FogDensity * vertexDistance), 0.0, 1.0);
        color = mix(FogColor, color, f);
    }
    else if (FogMode == 3) {
        float d = FogDensity * vertexDistance;
        float f = clamp(exp(-d * d), 0.0, 1.0);
        color = mix(FogColor, color, f);
    }

    fragColor = color;
}
