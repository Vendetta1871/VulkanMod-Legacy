#version 450

// Sunrise/sunset glow vertex shader (DefaultVertexFormats.POSITION_COLOR).
// The fan's per-vertex colors replace the primary color in the GL fixed
// pipeline, so no ColorModulator is applied in the fragment stage.

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
