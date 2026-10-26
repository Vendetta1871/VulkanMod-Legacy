#version 450

// Flat GUI rectangle fragments. The vertex colour replaces the primary colour in the
// fixed-function pipeline, which is still modulated by the current GL colour
// (GL_MODULATE against GL_PRIMARY_COLOR), hence the ColorModulator multiply.

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

layout(location = 0) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * vertexColor;

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

    fragColor = color;
}
