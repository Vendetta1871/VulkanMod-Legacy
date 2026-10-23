#version 450

// Sun/moon vertex shader (DefaultVertexFormats.POSITION_TEX).

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
};

layout(location = 0) out vec2 texCoord0;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);
    texCoord0 = UV0;
}
