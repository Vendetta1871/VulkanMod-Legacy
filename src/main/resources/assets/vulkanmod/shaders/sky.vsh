#version 450

// Sky vertex shader (DefaultVertexFormats.POSITION): used for the sky plane
// (fog enabled, sky color from ColorModulator) and the stars (FogMode 0,
// star brightness in ColorModulator).

layout(location = 0) in vec3 Position;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    int FogRadial;
};

layout(location = 0) out float vertexDistance;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    // GL measures linear fog by eye-space depth (|z|), or by radial distance when
    // the GL_NV_fog_distance extension is present; vanilla picks the radial mode
    // through glFogi(GL_FOG_DISTANCE_MODE_NV), mirrored into FogRadial (R16).
    vertexDistance = FogRadial != 0 ? length(viewPos.xyz) : abs(viewPos.z);
}
