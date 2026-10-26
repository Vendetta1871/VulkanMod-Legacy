#version 450

// Cloud vertex shader (DefaultVertexFormats.POSITION_TEX_COLOR). The cloud grid is
// persistent geometry (M12): the baked vertices carry the cell-local position and UV,
// the per-frame scroll/camera offset rides the modelview (XZ/Y) and UvOffset (UV).

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    vec2 UvOffset;
};

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out float vertexDistance;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    texCoord0 = UV0 + UvOffset;
    vertexColor = Color;
    vertexDistance = abs(viewPos.z);
}
