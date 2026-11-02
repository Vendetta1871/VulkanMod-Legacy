#version 450

// GUI fragments: texture modulated by the current GL colour (ColorModulator), with
// the fixed-function alpha test (GREATER 0.1) the HUD enables before drawing. Fog is
// only active for world-space text (name tags, sign text) drawn through the immediate
// capture with the live world matrices; the GUI pass sets FogMode 0 (FOG_NONE).

layout(binding = 1) uniform UniformBufferObject {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
    float AlphaFunc;
    float AlphaRef;
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in float vertexDistance;

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

    // GL fog replaces only the fragment RGB; the alpha passes through untouched.
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
