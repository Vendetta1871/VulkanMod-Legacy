#version 450

// Debug-box / selection-box fragments. With the vertex-color array active and lighting
// off, the fixed-function primary color is the vertex color itself (glColor is ignored),
// so there is no ColorModulator multiply here - only the same fixed-function fog the
// terrain/sky/clouds apply, blended toward FogColor on the RGB while the alpha (the
// blend weight of the translucent selection box) passes through untouched.

layout(binding = 1) uniform UniformBufferObject {
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor;

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
