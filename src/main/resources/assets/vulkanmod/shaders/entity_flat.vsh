#version 450

// Flat entity-pass overlay vertex shader (DefaultVertexFormats.POSITION_TEX):
//   loc 0: POSITION float3, loc 1: TEX0 float2.
// Covers the burning-entity fire quads, held maps and glint-style overlays that
// RenderItem / doRenderShadowAndFire push through the shared BufferBuilder. Drawn
// unlit (lightAccum = 1); the pass sets its own colour and blend.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float lightAccum;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = length(viewPos.xyz);
    texCoord0 = UV0;
    lightAccum = 1.0;
}
