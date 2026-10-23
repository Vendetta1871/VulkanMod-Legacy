#version 450

// Tinted textured GUI fragments: texture * vertex colour * ColorModulator, matching
// the fixed-function GL_MODULATE chain (GL_TEXTURE * GL_PRIMARY_COLOR * GL_PREVIOUS).

layout(binding = 1) uniform UniformBufferObject {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
    int AlphaTest;
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * vertexColor * texture(Sampler0, texCoord0);

    if (AlphaTest != 0 && color.a < 0.1) {
        discard;
    }

    fragColor = color;
}
