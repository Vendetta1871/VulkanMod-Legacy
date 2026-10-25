#version 450

// Flat entity-pass overlay fragments: ColorModulator * texture, live alpha test and
// fog. Used for burning fire, held maps and other POSITION_TEX overlays.

layout(binding = 1) uniform UniformBufferObject {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
    float AlphaRef;
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float lightAccum;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * texture(Sampler0, texCoord0);

    if (color.a < AlphaRef) {
        discard;
    }

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
