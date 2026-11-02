#version 450

// Diagnostic: real matrices and attributes, but no discard, lightmap or fog.
// If terrain shows up, the geometry path is fine and the fault is in the
// alpha test / lighting / fog of the real fragment shader.

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
    vec4 color = vertexColor * ColorModulator * texture(Sampler0, texCoord0);
    fragColor = vec4(color.rgb, 1.0);
}
