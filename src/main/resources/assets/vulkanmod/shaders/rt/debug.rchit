#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_GOOGLE_include_directive : require
#define RT_HIT
#include "common.glsl"

layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 bary;

void main() {
    if ((gl_InstanceCustomIndexEXT & INSTANCE_ENTITY) != 0) {
        // Entities: one color per model face, the entity's light.
        uint64_t base = instanceData.vertexAddress[gl_InstanceID];
        uint quads = uint(gl_InstanceCustomIndexEXT & 0x3FFFFF);
        uvec2 face = EntityQuads(base).q[gl_PrimitiveID >> 1];
        EntityPositions positions = EntityPositions(base + uint64_t(quads) * 8ul);
        uint first = uint(gl_PrimitiveID >> 1) * 4u;
        bool second = (gl_PrimitiveID & 1) != 0;
        vec3 pa = positions.p[first], pb = positions.p[first + (second ? 2u : 1u)], pc = positions.p[first + (second ? 3u : 2u)];
        vec3 cr = cross(pb - pa, pc - pa);
        float len2 = dot(cr, cr);
        vec3 en = len2 > 1e-20 ? cr * inversesqrt(len2) : -gl_WorldRayDirectionEXT;
        if (dot(en, gl_WorldRayDirectionEXT) > 0.0) en = -en;
        payload.albedo = unpackUnorm4x8(face.x).rgb;
        payload.t = gl_HitTEXT;
        payload.normal = en;
        payload.skyLight = float(face.y & 0xFFu) / 15.0;
        payload.blockLight = float((face.y >> 8) & 0xFFu) / 15.0;
        payload.tinted = 0.0;
        payload.material = vec4(0.0);
        return;
    }
    ChunkVertex a, b, c;
    fetchTriangle(a, b, c);
    vec3 w = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);

    vec2 uv = a.uv * w.x + b.uv * w.y + c.uv * w.z;
    vec4 tint = unpackUnorm4x8(a.color) * w.x + unpackUnorm4x8(b.color) * w.y + unpackUnorm4x8(c.color) * w.z;
    vec3 albedo = textureLod(blockAtlas, uv, 0.0).rgb * tint.rgb;

    // Degenerate (zero-area) triangles have no normal: face the ray instead. A NaN normal
    // would turn into NaN secondary rays, which software traversal can spin on.
    vec3 cr = cross(b.pos - a.pos, c.pos - a.pos);
    float len2 = dot(cr, cr);
    vec3 n = len2 > 1e-20 ? cr * inversesqrt(len2) : -gl_WorldRayDirectionEXT;
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) n = -n;

    float sky = (float((a.light >> 16) & 0xFFFFu) * w.x + float((b.light >> 16) & 0xFFFFu) * w.y
            + float((c.light >> 16) & 0xFFFFu) * w.z) / 240.0;
    float block = (float(a.light & 0xFFFFu) * w.x + float(b.light & 0xFFFFu) * w.y
            + float(c.light & 0xFFFFu) * w.z) / 240.0;

    payload.albedo = albedo;
    payload.t = gl_HitTEXT;
    payload.normal = n;
    payload.skyLight = clamp(sky, 0.0, 1.0);
    payload.blockLight = clamp(block, 0.0, 1.0);
    payload.tinted = tint.r + tint.g + tint.b < 2.95 ? 1.0 : 0.0;
    payload.material = textureLod(materialAtlas, uv, 0.0);
}
