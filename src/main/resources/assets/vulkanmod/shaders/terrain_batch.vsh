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

// binding 0 (MVP UBO) is intentionally unused here; it is kept only so the descriptor
// layout matches terrain_batch.json's numbering (fog=1, Sampler0=2, Sampler2=3, SSBO=4).
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

    // The GL fixed-function fog coordinate is the eye-space DEPTH (|z|), not the
    // radial distance - radial fog would be thicker at the screen edges than GL (L2).
    vertexDistance = abs(viewPos.z);

    vertexColor = Color;
    texCoord0 = UV0;

    // Lightmap sampling. Vanilla never sets a texture filter on the lightmap (GL
    // default NEAREST), but the Kirino GL reference samples it LINEAR with the texel
    // position taken as coord*size (no -0.5 texel correction), so a whole light level
    // lands on the texel center instead of its left edge. The mirror uses a LINEAR
    // sampler and this +0.5 texel shift reproduces that: verified on aligned pairs -
    // unshifted reads 0.885x of the reference at noon, NEAREST 0.88x at night, this
    // convention matches both (median per-pixel ratio 1.000).
    lightCoord = clamp(vec2(UV2) / 256.0, vec2(0.001953126), vec2(0.96875)) + vec2(0.03125);
}
