#version 450

// Diagnostic: outputs the atlas texel at the screen-mapped UV.

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 2) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(Sampler0, texCoord0);
}
