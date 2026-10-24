#version 450

// Diagnostic variant: flat pseudo-random color per chunk-section slot, so seams
// between sections are visible. Black = slot 0.

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec2 lightCoord;
layout(location = 4) in float slotIn;

layout(location = 0) out vec4 fragColor;

void main() {
    float s = slotIn + 1.0;
    fragColor = vec4(fract(s * 0.3710), fract(s * 0.7131), fract(s * 0.1379), 1.0);
}
