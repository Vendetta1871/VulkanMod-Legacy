#version 450

// Sky vertex shader (DefaultVertexFormats.POSITION): used for the sky plane
// (fog enabled, sky color from ColorModulator) and the stars (FogMode 0,
// star brightness in ColorModulator).

layout(location = 0) in vec3 Position;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
};

layout(location = 0) out float vertexDistance;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    // The GL fixed-function fog coordinate is the eye-space DEPTH (|z|), not the
    // radial distance - radial fog would be thicker at the screen edges than GL (L2).
    vertexDistance = abs(viewPos.z);
}
