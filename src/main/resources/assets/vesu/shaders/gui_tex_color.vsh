#version 450

// Tinted textured GUI vertex shader (DefaultVertexFormats.POSITION_TEX_COLOR):
// the pumpkin blur / portal / damage overlays of GuiIngame.renderPumpkinOverlay and
// friends, where the fade colour is carried per vertex.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
};

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
