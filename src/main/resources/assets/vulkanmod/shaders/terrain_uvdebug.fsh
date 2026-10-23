#version 450

// Diagnostic variant: R/G show the tiled atlas UVs, B shows the atlas content.
// A uniform black R/G means the UV attribute is constant (vertex data fault);
// a gradient pattern means UVs and geometry are fine.

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
    // Raw UV readout: R = uv.x * 255, G = uv.y * 255 (exact values readable from pixels).
    fragColor = vec4(texCoord0.x, texCoord0.y, 0.5, 1.0);
}
