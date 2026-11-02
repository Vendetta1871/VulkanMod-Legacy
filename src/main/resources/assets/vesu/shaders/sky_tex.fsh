#version 450

// Sun/moon fragments: texture modulated by ColorModulator (whose alpha carries
// the 1 - rainStrength fade). No fog and no alpha discard, matching the GL path
// (disableFog/disableAlpha before the sun is drawn).

layout(binding = 1) uniform UniformBufferObject {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = ColorModulator * texture(Sampler0, texCoord0);
}
