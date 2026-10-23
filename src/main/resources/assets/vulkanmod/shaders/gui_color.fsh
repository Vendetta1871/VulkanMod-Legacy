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
    int AlphaTest;
};

layout(location = 0) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * vertexColor;

    if (AlphaTest != 0 && color.a < 0.1) {
        discard;
    }

    fragColor = color;
}
