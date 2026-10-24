#version 450

// Dropped-item fragments: atlas texel modulated by the item tint (vertex colour), the
// ColorModulator and the directional shading, with the live alpha test and the same
// hurt/death red overlay and fog as the mob model pass.

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
layout(location = 3) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * vertexColor * texture(Sampler0, texCoord0);

    if (color.a < AlphaRef) {
        discard;
    }

    color.rgb *= lightAccum;

    color.rgb = mix(color.rgb, vec3(1.0, 0.0, 0.0), RedOverlay);

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
