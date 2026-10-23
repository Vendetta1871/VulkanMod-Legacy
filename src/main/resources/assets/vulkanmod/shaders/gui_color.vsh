#version 450

// Flat GUI rectangle vertex shader (DefaultVertexFormats.POSITION_COLOR):
// Gui.drawRect / drawGradientRect backgrounds, selection highlights, bars.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
};

layout(location = 0) out vec4 vertexColor;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);
    vertexColor = Color;
}
