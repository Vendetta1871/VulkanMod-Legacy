#version 450
// LOD terrain vertex stage (AUDIT 5.15): vertex pulling through the quad index buffer.
// gl_VertexIndex / 4 is the quad in the arena, the remainder its corner; gl_InstanceIndex
// the section record.
#extension GL_GOOGLE_include_directive : require
#include "lod_common.glsl"

layout(std430, set = 0, binding = 1) readonly buffer Quads { uvec2 quads[]; };
layout(std430, set = 0, binding = 2) readonly buffer Sections { ivec4 sections[]; };  // origin in blocks, level
layout(std430, set = 0, binding = 4) readonly buffer Tints { uint tints[]; };

layout(location = 0) out vec3 vRel;
layout(location = 1) out vec2 vUv;
layout(location = 2) flat out uvec4 vInfo;   // model, face | lvl << 4, light, tint (ARGB, 0 = none)
layout(location = 3) flat out uvec2 vFace;   // the face data and flags of the model (no per-fragment reads)

const int CU[4] = int[](0, 1, 1, 0);
const int CV[4] = int[](0, 0, 1, 1);

void main() {
    int q = gl_VertexIndex >> 2;
    int c = gl_VertexIndex & 3;
    uvec2 qd = quads[q];
    uint lo = qd.x, hi = qd.y;
    int face = int(lo & 7u);
    int w = int((lo >> 3) & 15u) + 1;
    int h = int((lo >> 7) & 15u) + 1;
    int z = int((lo >> 11) & 31u);
    int y = int((lo >> 16) & 31u);
    int x = int((lo >> 21) & 31u);
    uint model = (lo >> 26) | ((hi & 0x3FFFu) << 6);
    uint biome = (hi >> 14) & 0x1FFu;
    uint light = (hi >> 23) & 0xFFu;
    ivec4 sec = sections[gl_InstanceIndex];
    float scale = float(1 << sec.w);

    float cu = float(CU[c] * w), cv = float(CV[c] * h);
    uint fd = models[model].faceData[face];
    float indent = float((fd >> 16) & 63u) / 64.0;
    int axis = face >> 1;
    float plane = (face & 1) == 1 ? 1.0 - indent : indent;
    vec3 p;
    if (axis == 0) p = vec3(float(x) + cu, float(y) + plane, float(z) + cv);
    else if (axis == 1) p = vec3(float(x) + cu, float(y) + cv, float(z) + plane);
    else p = vec3(float(x) + plane, float(y) + cv, float(z) + cu);

    vec3 rel = vec3(sec.xyz - camBlock.xyz) + p * scale - camFrac.xyz;
    gl_Position = viewProj * vec4(rel, 1.0);
    vRel = rel;
    vUv = vec2(cu, cv);

    uint flags = models[model].flags;
    uint tint = 0u;
    if ((flags & 1u) != 0u) {
        tint = (flags & 2u) != 0u ? tints[models[model].tint + biome] : models[model].tint;
        tint |= 0xFF000000u;
    }
    vInfo = uvec4(model, uint(face) | uint(sec.w) << 4, light, tint);
    vFace = uvec2(fd, flags);
}
