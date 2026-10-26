#version 450

// GUI vertex shader (DefaultVertexFormats.POSITION_TEX): hotbar, crosshair, icons
// and FontRenderer glyphs. The live GL matrices carry the overlay ortho projection
// (setupOverlayRendering) plus any per-element translate/scale, so the vertices are
// transformed exactly like the fixed-function GL path.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
};

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out float vertexDistance;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = MVP * vec4(Position, 1.0);
    texCoord0 = UV0;
    // World-space text (name tags, sign text) renders with the live world matrices
    // and the frame's fog; the GUI pass sets FOG_NONE so this stays unused there.
    vertexDistance = length(viewPos.xyz);
}
