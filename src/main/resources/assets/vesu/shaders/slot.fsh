#version 450

layout(binding = 1) uniform UniformBufferObject {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
};

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec2 lightCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator * texture(Sampler0, texCoord0);

    // Replaces the GL_ALPHA_TEST (GREATER, 0.1) of the cutout layers; fully
    // transparent pixels of the translucent layer blend to a no-op anyway.
    if (color.a < 0.1) {
        discard;
    }

    // Vanilla lightmap: the fixed-function path modulates the block colour with the
    // lightmap texture directly (GL_MODULATE on the lightmap unit, lighting disabled),
    // so no scaling is applied here. The lightmap saturates to ~white in full daylight,
    // which is why any extra factor is invisible at noon but brightens dusk, night and
    // caves by the same factor (the dumped table reads 57/255 for an exposed block at
    // midnight, i.e. vanilla renders night terrain at ~0.22).
    vec4 lightmap = texture(Sampler2, lightCoord);
    color.rgb *= lightmap.rgb;

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
