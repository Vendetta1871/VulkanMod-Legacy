#version 450

// Debug-box / selection-box vertex shader (DefaultVertexFormats.POSITION_COLOR). The
// geometry is built eye-relative (the renderers subtract the camera position), so the
// fog distance is the length of the view-space position, matching the fixed-function
// fog coordinate. No texture coordinate: the renderers draw with texture2D disabled.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
};

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out float vertexDistance;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    vertexColor = Color;
    vertexDistance = abs(viewPos.z);
}
