#version 450

// GUI fragments: texture modulated by the current GL colour (ColorModulator), with
// the fixed-function alpha test (GREATER 0.1) the HUD enables before drawing. No fog:
// the overlay is drawn with fog disabled.

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

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator * texture(Sampler0, texCoord0);

    if (AlphaTest != 0 && color.a < 0.1) {
        discard;
    }

    fragColor = color;
}
