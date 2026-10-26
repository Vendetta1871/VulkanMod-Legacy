#version 450

// Dropped-item model vertex shader (DefaultVertexFormats.ITEM):
//   loc 0: POSITION float3, loc 1: COLOR ubyte4 (UNORM), loc 2: TEX0 float2,
//   loc 3: NORMAL byte3 (SNORM), loc 4: PADDING byte1 (alignment, unused).
// RenderItem bakes the item tint into the vertex colour and the baked model normals
// drive the same two-light fixed-function shading the mob models use.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 VertexColor;
layout(location = 2) in vec2 UV0;
layout(location = 3) in vec3 Normal;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    mat4 TextureMat;
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
    // The enchantment glint scrolls its UVs through the GL texture matrix (the only
    // vanilla draw that touches the texture stack); every other item keeps it identity.
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    vertexColor = VertexColor;

    // Same standard item lighting as the mob model shader (see entity.vsh).
    vec3 normal = normalize(mat3(ModelViewMat) * Normal);
    float diffuse = max(0.0, dot(normalize(Light0_Direction), normal))
                  + max(0.0, dot(normalize(Light1_Direction), normal));

    lightAccum = clamp(0.4 + 0.6 * diffuse, 0.0, 1.0);
}
