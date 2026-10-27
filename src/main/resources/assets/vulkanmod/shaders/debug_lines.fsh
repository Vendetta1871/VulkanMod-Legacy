#version 450

// Debug-box / selection-box fragments. With the vertex-color array active and lighting
// off, the fixed-function primary color is the vertex color itself (glColor is ignored),
// so there is no ColorModulator multiply here - only the same fixed-function fog the
// terrain/sky/clouds apply, blended toward FogColor on the RGB while the alpha (the
// blend weight of the translucent selection box) passes through untouched. The same
// alpha test as the terrain discards the alpha-0 "pen-up" joints vanilla hides with
// GREATER 0.1 (R18).

layout(binding = 1) uniform UniformBufferObject {
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
    float AlphaFunc;
    float AlphaRef;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor;

    // Fixed-function alpha test, same table as terrain.fsh (R6-correct enums).
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
