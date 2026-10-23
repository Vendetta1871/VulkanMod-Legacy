#version 450

// Cloud vertex shader (DefaultVertexFormats.POSITION_TEX_COLOR). The cloud grid is
// built in eye-relative space (see CloudRenderer), so the fog distance is just the
// length of the view-space position, matching the fixed-function fog coordinate.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
};

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out float vertexDistance;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
    vertexDistance = length(viewPos.xyz);
}
