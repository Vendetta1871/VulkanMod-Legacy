#version 450

// Batched terrain vertex shader. Identical output to terrain.vsh, except the per-chunk
// transform is no longer a per-draw MVP uniform: every chunk section is uploaded into a
// shared vertex arena with an extra per-vertex attribute (location 4) carrying its section
// slot, and the MVP / ModelView matrices are read from the ChunkMatrices storage buffer
// indexed by that slot. This lets a whole render layer be submitted as one indirect draw.
//
// Vertex layout (1.12.2 DefaultVertexFormats.BLOCK + a trailing float "ChunkSlot"):
//   loc 0: POSITION float3, loc 1: COLOR ubyte4 (normalized),
//   loc 2: TEX0 float2 (atlas uv), loc 3: TEX2 short2 (lightmap uv * 256),
//   loc 4: ChunkSlot float (this section's index into ChunkMatrices)

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
layout(location = 4) in float ChunkSlot;

// binding 0 carries only the FogRadial fog metric (R16); the MVP / ModelViewMat fields
// are unused here (the matrices come from the ChunkMatrices SSBO below), but the block
// layout must match terrain_batch.json so the descriptor numbering stays identical
// (fog=1, Sampler0=2, Sampler2=3, SSBO=4).
layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    int FogRadial;
    // Dynamic lights ("fast" mode): view-space position, block light level (0 = unused).
    vec4 DynLights[16];
};

struct ChunkMatrix {
    mat4 mvp;
    mat4 modelView;
};

layout(std430, binding = 4) readonly buffer ChunkMatrices {
    ChunkMatrix chunks[];
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec2 lightCoord;

void main() {
    ChunkMatrix cm = chunks[int(ChunkSlot)];

    vec4 viewPos = cm.modelView * vec4(Position, 1.0);
    gl_Position = cm.mvp * vec4(Position, 1.0);

    // GL measures linear fog by eye-space depth (|z|), or by radial distance when
    // the GL_NV_fog_distance extension is present; vanilla picks the radial mode
    // through glFogi(GL_FOG_DISTANCE_MODE_NV), mirrored into FogRadial (R16).
    vertexDistance = FogRadial != 0 ? length(viewPos.xyz) : abs(viewPos.z);

    vertexColor = Color;
    texCoord0 = UV0;

    // Lightmap sampling. Vanilla never sets a texture filter on the lightmap (GL
    // default NEAREST), but the Kirino GL reference samples it LINEAR with the texel
    // position taken as coord*size (no -0.5 texel correction), so a whole light level
    // lands on the texel center instead of its left edge. The mirror uses a LINEAR
    // sampler and this +0.5 texel shift reproduces that: verified on aligned pairs -
    // unshifted reads 0.885x of the reference at noon, NEAREST 0.88x at night, this
    // convention matches both (median per-pixel ratio 1.000).
    // Dynamic lights raise the block light like a placed light of their level would.
    vec2 light = vec2(UV2);
    for (int i = 0; i < 16; i++) {
        vec4 dl = DynLights[i];
        if (dl.w > 0.0) light.x = max(light.x, clamp(dl.w - distance(viewPos.xyz, dl.xyz), 0.0, 15.0) * 16.0);
    }
    lightCoord = clamp(light / 256.0, vec2(0.001953126), vec2(0.96875)) + vec2(0.03125);
}
