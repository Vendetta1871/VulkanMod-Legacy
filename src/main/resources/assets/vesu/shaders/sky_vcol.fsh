#version 450

// Sunrise/sunset glow fragments. GL_SHADE_MODEL_SMOOTH of the original draw is
// reproduced by the interpolator feeding the per-vertex colors here.

layout(location = 0) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vertexColor;
}
