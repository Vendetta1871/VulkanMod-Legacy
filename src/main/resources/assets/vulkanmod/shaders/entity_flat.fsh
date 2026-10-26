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
    float AlphaFunc;
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
        else if (alphaFunc == 517) keep = color.a >= AlphaRef;                // GEQUAL
        else if (alphaFunc == 518) keep = abs(color.a - AlphaRef) >= 0.0039;  // NOTEQUAL
        else keep = true;                                                      // ALWAYS
        if (!keep) {
            discard;
        }
    }

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
