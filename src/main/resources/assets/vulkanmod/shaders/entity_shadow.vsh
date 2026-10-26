#version 450

// Entity drop-shadow vertex shader (DefaultVertexFormats.POSITION_TEX_COLOR):
//   loc 0: POSITION float3, loc 1: TEX0 float2, loc 2: COLOR ubyte4 (UNORM).
// Render.renderShadow projects shadow.png quads onto the ground blocks under the
// entity; the fixed-function path draws them unlit (the shadow texel is black), so
// lightAccum stays at 1.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 VertexColor;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float lightAccum;
layout(location = 3) out vec4 vertexColor;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = abs(viewPos.z);
    texCoord0 = UV0;
    vertexColor = VertexColor;
    lightAccum = 1.0;
}
