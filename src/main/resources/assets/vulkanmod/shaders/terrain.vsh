#version 450

// Terrain vertex shader for the 1.12.2 DefaultVertexFormats.BLOCK layout:
//   loc 0: POSITION float3, loc 1: COLOR ubyte4 (normalized),
//   loc 2: TEX0 float2 (atlas uv), loc 3: TEX2 short2 (lightmap uv * 256)
// The chunk offset is baked into MVP/ModelViewMat by the GL matrix state, so no
// separate ChunkOffset push constant is needed here.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec2 lightCoord;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    // Matches the GL fixed-function fog coordinate (fragment depth = eye distance).
    vertexDistance = length(viewPos.xyz);

    vertexColor = Color;
    texCoord0 = UV0;

    // Lightmap sampling; see terrain_batch.vsh: +0.5 texel shift reproduces the
    // Kirino reference's LINEAR texel-index sampling (vanilla itself is NEAREST).
    lightCoord = clamp(vec2(UV2) / 256.0, vec2(0.001953126), vec2(0.96875)) + vec2(0.03125);
}
