#version 450

// Particle fragments: the particle-atlas (or block-atlas, for block-dust particles)
// texel modulated by the per-particle vertex colour and the lightmap, with the live
// alpha test (1/255 while the pass runs) and the same fog as the terrain.

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
layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 lightCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * vertexColor * texture(Sampler0, texCoord0);

    if (color.a < AlphaRef) {
        discard;
    }

    color.rgb *= texture(Sampler2, lightCoord).rgb;

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
