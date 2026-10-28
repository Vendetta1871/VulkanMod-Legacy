// Shared RT declarations: chunk vertex fetch through the per-instance vertex address.
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// ChunkBatcher.FORMAT: BLOCK (pos, color, uv, lightmap) + section slot; 32 bytes.
struct ChunkVertex {
    vec3 pos;
    uint color;    // RGBA8
    vec2 uv;
    uint light;    // two shorts: block light, sky light (0..240)
    float slot;
};

layout(buffer_reference, scalar, buffer_reference_align = 4) readonly buffer ChunkVertices {
    ChunkVertex v[];
};

// Indexed by gl_InstanceID: device address of the section's first vertex in the arena.
layout(set = 0, binding = 2, scalar) readonly buffer InstanceData {
    uint64_t vertexAddress[];
} instanceData;

layout(set = 0, binding = 3) uniform sampler2D blockAtlas;
#ifdef RT_HIT
layout(set = 0, binding = 16) uniform sampler2D materialAtlas;
#endif

struct Payload {
    vec3 albedo;
    float t;          // < 0: miss
    vec3 normal;
    float skyLight;   // 0..1
    float blockLight; // 0..1
    float tinted;     // 1: vertex-tinted (water, grass, leaves), 0: white
    vec4 material;    // LabPBR specular: smoothness, F0 (>= 230/255: metal), porosity, emission
};

// Shadow rays (payload location 1, hit group 1): rgb transmittance through stained glass
// and ice, a: 1 when the ray reached its end (miss shader).
#define SHADOW_HIT_GROUP 1
// Custom index bit of translucent sections (water, glass, ice).
#define INSTANCE_TRANSLUCENT 0x800000

#ifdef RT_HIT
// Quads are indexed (0,1,2), (0,2,3): the triangle follows from gl_PrimitiveID alone.
void fetchTriangle(out ChunkVertex a, out ChunkVertex b, out ChunkVertex c) {
    ChunkVertices verts = ChunkVertices(instanceData.vertexAddress[gl_InstanceID]);
    uint base = uint(gl_PrimitiveID >> 1) * 4u;
    bool second = (gl_PrimitiveID & 1) != 0;
    a = verts.v[base];
    b = verts.v[base + (second ? 2u : 1u)];
    c = verts.v[base + (second ? 3u : 2u)];
}

vec2 interpolateUv(ChunkVertex a, ChunkVertex b, ChunkVertex c, vec2 bary) {
    return a.uv * (1.0 - bary.x - bary.y) + b.uv * bary.x + c.uv * bary.y;
}
#endif // RT_HIT
