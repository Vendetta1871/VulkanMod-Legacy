#version 450
// Host source of the pack programs that draw distant terrain (AUDIT 5.16, contract B: Iris'
// dh_terrain and dh_water). Not compiled itself: GeometryPrelude copies its blocks into the
// pack program's prelude, and the block between the vkm-host-prelude markers, which pulls
// the LOD quad of the vertex and stands in for the vertex attributes of a vanilla format.
// For the pack's own terrain programs it also stands in for mc_Entity and at_tangent.
// The values follow Distant Horizons' vertices: the face's average color with its tint, the
// baked light as a lightmap coordinate, the face normal and the DH material id.

struct LodModel {
    uint faceData[6];
    uint flags;
    uint tint;
    uint customId;
    uint faceColor[6];
    uint material;
};

layout(std140, binding = 0) uniform LodPackFrame {
    mat4 MVP;
    mat4 ModelViewMat;
    ivec4 LodCamBlock;
    vec4 LodCamFrac;
    mat4 LodInvMVP;
    ivec4 LodCover;     // x, z: chunk of the coverage grid's corner, y: grid size in chunks, w: 1 when valid
    vec4 LodScreen;     // x, y: size in pixels, z: -1 when the viewport flips y, w: 1 drops covered opaque too
};
layout(std430, binding = 1) readonly buffer LodQuads { uvec2 lodQuads[]; };
layout(std430, binding = 2) readonly buffer LodSections { ivec4 lodSections[]; };
layout(std430, binding = 3) readonly buffer LodModels { LodModel lodModels[]; };
layout(std430, binding = 4) readonly buffer LodTints { uint lodTints[]; };
// Per model: the pack's block.properties attribute of its block state (PackIds.block).
layout(std430, binding = 6) readonly buffer LodPackIds { uint lodPackIds[]; };

//vkm-host-prelude-begin
const int VKM_LOD_CU[4] = int[](0, 1, 1, 0);
const int VKM_LOD_CV[4] = int[](0, 0, 1, 1);
const vec3 VKM_LOD_NORMALS[6] = vec3[](vec3(0, -1, 0), vec3(0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1), vec3(-1, 0, 0), vec3(1, 0, 0));

// Drawn through the quad index buffer: 4 vertices per quad.
uvec2 vkm_lodQuad() { return lodQuads[gl_VertexIndex >> 2]; }
int vkm_lodFace() { return int(vkm_lodQuad().x & 7u); }
uint vkm_lodModel() { uvec2 q = vkm_lodQuad(); return (q.x >> 26) | ((q.y & 0x3FFFu) << 6); }

vec3 vkm_lodPosition() {
    uvec2 qd = vkm_lodQuad();
    uint lo = qd.x;
    int c = gl_VertexIndex & 3;
    int face = int(lo & 7u);
    int w = int((lo >> 3) & 15u) + 1;
    int h = int((lo >> 7) & 15u) + 1;
    int z = int((lo >> 11) & 31u);
    int y = int((lo >> 16) & 31u);
    int x = int((lo >> 21) & 31u);
    ivec4 sec = lodSections[gl_InstanceIndex];
    float cu = float(VKM_LOD_CU[c] * w), cv = float(VKM_LOD_CV[c] * h);
    uint fd = lodModels[vkm_lodModel()].faceData[face];
    float indent = float((fd >> 16) & 63u) / 64.0;
    int axis = face >> 1;
    float plane = (face & 1) == 1 ? 1.0 - indent : indent;
    vec3 p;
    if (axis == 0) p = vec3(float(x) + cu, float(y) + plane, float(z) + cv);
    else if (axis == 1) p = vec3(float(x) + cu, float(y) + cv, float(z) + plane);
    else p = vec3(float(x) + plane, float(y) + cv, float(z) + cu);
    return vec3(sec.xyz - LodCamBlock.xyz) + p * float(1 << sec.w) - LodCamFrac.xyz;
}

vec4 vkm_lodColor() {
    uint model = vkm_lodModel();
    int face = vkm_lodFace();
    uint c = lodModels[model].faceColor[face];
    vec4 color = vec4(float(c & 255u), float((c >> 8) & 255u), float((c >> 16) & 255u), 255.0) / 255.0;
    uint flags = lodModels[model].flags;
    uint tintMode = (lodModels[model].faceData[face] >> 24) & 3u;
    if ((flags & 1u) != 0u && tintMode != 0u) {
        uint biome = (vkm_lodQuad().y >> 14) & 0x1FFu;
        uint t = (flags & 2u) != 0u ? lodTints[lodModels[model].tint + biome] : lodModels[model].tint;
        vec3 tint = vec3(float((t >> 16) & 255u), float((t >> 8) & 255u), float(t & 255u)) / 255.0;
        // A partly tinted face (grass block sides) tints part of its average.
        color.rgb *= tintMode == 2u ? tint : mix(vec3(1.0), tint, 0.5);
    }
    return color;
}

ivec2 vkm_lodLight() {
    uint light = (vkm_lodQuad().y >> 23) & 0xFFu;
    return ivec2(int(light >> 4) * 16 + 8, int(light & 15u) * 16 + 8);
}

// The camera-relative position for the fragment stage: the shadow map's distance cut,
// which the fragment's coordinates cannot give once a pack distorts its map.
layout(location = 31) out vec3 vkmLodRel;

vec3 vkm_lodPositionOut() {
    vec3 p = vkm_lodPosition();
    vkmLodRel = p;
    return p;
}

#define vaPosition vkm_lodPositionOut()
#define vaColor vkm_lodColor()
#define vaUV0 vec2(0.0)
#define vaUV2 vkm_lodLight()
#define vaNormal VKM_LOD_NORMALS[vkm_lodFace()]
#define dhMaterialId int(lodModels[vkm_lodModel()].material)

// For the pack's own terrain programs (no DH programs).
vec4 vkm_lodEntity() {
    uint v = lodPackIds[vkm_lodModel()];
    return vec4(float(v & 0xFFFFu), float(v >> 24), float((v >> 16) & 255u), 1.0);
}

vec4 vkm_lodTangent() {
    return vkm_lodFace() >= 4 ? vec4(0.0, 0.0, 1.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
}

#define mc_Entity vkm_lodEntity()
#define at_tangent vkm_lodTangent()
//vkm-host-prelude-end

void main() {
}
