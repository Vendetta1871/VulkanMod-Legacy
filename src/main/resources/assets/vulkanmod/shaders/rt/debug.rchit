#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_GOOGLE_include_directive : require
#define RT_HIT
#include "common.glsl"

layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 bary;

void main() {
    ChunkVertex a, b, c;
    fetchTriangle(a, b, c);
    vec3 w = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);

    vec2 uv = a.uv * w.x + b.uv * w.y + c.uv * w.z;
    vec4 tint = unpackUnorm4x8(a.color) * w.x + unpackUnorm4x8(b.color) * w.y + unpackUnorm4x8(c.color) * w.z;
    vec3 albedo = textureLod(blockAtlas, uv, 0.0).rgb * tint.rgb;

    vec3 n = normalize(cross(b.pos - a.pos, c.pos - a.pos));
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
}
