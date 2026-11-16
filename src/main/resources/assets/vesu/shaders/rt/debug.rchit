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
        payload.material = vec4(0.0);
        if ((face.y & 0x80000000u) != 0u) {
            // Block-atlas face (dropped blocks, items): its texture and material.
            vec2 uv = vec2(face.x & 0xFFFFu, face.x >> 16) / 65535.0;
            payload.albedo = textureLod(blockAtlas, uv, 0.0).rgb;
            payload.material = textureLod(materialAtlas, uv, 0.0);
        } else {
            payload.albedo = unpackUnorm4x8(face.x).rgb;
        }
        payload.t = gl_HitTEXT;
        payload.normal = en;
        payload.shading = en;
        payload.skyLight = float(face.y & 0xFFu) / 15.0;
        payload.blockLight = float((face.y >> 8) & 0xFFu) / 15.0;
        payload.tinted = 0.0;
        payload.backface = 0.0;
        return;
    }
    ChunkVertex a, b, c;
    fetchTriangle(a, b, c);
    vec3 w = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);

    vec2 uv = a.uv * w.x + b.uv * w.y + c.uv * w.z;
    vec4 tint = unpackUnorm4x8(a.color) * w.x + unpackUnorm4x8(b.color) * w.y + unpackUnorm4x8(c.color) * w.z;
    // Mip level of the footprint (primary rays): 16 texels per block, widened on grazing faces.
    float lod = 0.0;
    if (payload.cone > 0.0) {
        vec3 gn = normalize(cross(b.pos - a.pos, c.pos - a.pos));
        float slope = max(abs(dot(gn, gl_WorldRayDirectionEXT)), 0.3);
        lod = max(log2(payload.cone * gl_HitTEXT * 16.0 / slope), 0.0);
    }
    // Parallax occlusion (primary rays) for a normal map with a height channel (LabPBR alpha,
    // 255 = the surface, lower = deeper, at most a quarter block): the view ray marched through
    // the height field in the face's tangent frame, kept inside the face's sprite.
    if (payload.cone > 0.0 && textureLod(normalAtlas, uv, lod).a < 0.998) {
        vec3 e1 = b.pos - a.pos, e2 = c.pos - a.pos;
        vec2 d1 = b.uv - a.uv, d2 = c.uv - a.uv;
        float det = d1.x * d2.y - d1.y * d2.x;
        vec3 gn = cross(e1, e2);
        if (abs(det) > 1e-12 && dot(gn, gn) > 1e-20) {
            gn = normalize(gn);
            if (dot(gn, gl_WorldRayDirectionEXT) > 0.0) gn = -gn;
            vec3 t = (e1 * d2.y - e2 * d1.y) / det;     // world per unit u
            vec3 bt = (e2 * d1.x - e1 * d2.x) / det;    // world per unit v
            vec3 v = -gl_WorldRayDirectionEXT;
            float vn = max(dot(v, gn), 0.15);
            // World offset per unit depth along the surface, in texture units.
            vec2 slope = vec2(dot(v, t) / dot(t, t), dot(v, bt) / dot(bt, bt)) / vn;
            const float DEPTH = 0.25;
            const int STEPS = 16;
            vec2 lo = min(a.uv, min(b.uv, c.uv)), hi = max(a.uv, max(b.uv, c.uv));
            vec2 half_ = 0.5 / vec2(textureSize(normalAtlas, 0));
            vec2 hitUv = uv;
            float prevDepth = 0.0, prevGap = 0.0;
            for (int i = 0; i <= STEPS; i++) {
                float depth = float(i) / float(STEPS);
                vec2 q = clamp(uv - slope * (depth * DEPTH), lo + half_, hi - half_);
                float gap = (1.0 - textureLod(normalAtlas, q, lod).a) - depth;
                if (gap <= 0.0) {
                    float f = i == 0 ? 0.0 : prevGap / max(prevGap - gap, 1e-6);
                    float hitDepth = mix(prevDepth, depth, f);
                    hitUv = clamp(uv - slope * (hitDepth * DEPTH), lo + half_, hi - half_);
                    break;
                }
                prevDepth = depth;
                prevGap = gap;
                hitUv = q;
            }
            uv = hitUv;
        }
    }
    vec3 albedo = textureLod(blockAtlas, uv, lod).rgb * tint.rgb;
    vec4 nmap = textureLod(normalAtlas, uv, lod);
    albedo *= nmap.b;   // LabPBR ambient occlusion, into the albedo as RTX Remix for Minecraft bakes it

    // Degenerate (zero-area) triangles have no normal: face the ray instead. A NaN normal
    // would turn into NaN secondary rays, which software traversal can spin on.
    vec3 cr = cross(b.pos - a.pos, c.pos - a.pos);
    float len2 = dot(cr, cr);
    vec3 n = len2 > 1e-20 ? cr * inversesqrt(len2) : -gl_WorldRayDirectionEXT;
    // Block faces wind counter-clockwise seen from outside: cross() points out of the block,
    // and a ray going the same way hits the face from behind (it started inside the block).
    payload.backface = len2 > 1e-20 && dot(n, gl_WorldRayDirectionEXT) > 0.0 ? 1.0 : 0.0;
    // Vertex colors carry vanilla's directional face shading (top 1, bottom 0.5, north/south
    // 0.8, east/west 0.6) besides biome tints and smooth-lighting occlusion. The shading is
    // lighting, not surface color: divided out on axis-aligned faces, the occlusion stays.
    vec3 an = abs(n);
    if (len2 > 1e-20 && max(an.x, max(an.y, an.z)) > 0.99) {
        float face = an.y > 0.5 ? (n.y > 0.0 ? 1.0 : 0.5) : (an.z > 0.5 ? 0.8 : 0.6);
        albedo = min(albedo / face, vec3(1.0));
    }
    // Shading normal from the normal map: tangent frame from the triangle's texture coordinates.
    vec3 ns = n;
    if (len2 > 1e-20 && (abs(nmap.r - 0.502) > 0.004 || abs(nmap.g - 0.502) > 0.004)) {
        vec3 e1 = b.pos - a.pos, e2 = c.pos - a.pos;
        vec2 d1 = b.uv - a.uv, d2 = c.uv - a.uv;
        float det = d1.x * d2.y - d1.y * d2.x;
        if (abs(det) > 1e-12) {
            vec3 t = (e1 * d2.y - e2 * d1.y) / det;
            vec3 bt = (e2 * d1.x - e1 * d2.x) / det;
            t = normalize(t - n * dot(n, t));
            bt = normalize(bt - n * dot(n, bt) - t * dot(t, bt));
            vec2 xy = nmap.rg * 2.0 - 1.0;
            vec3 m = normalize(t * xy.x + bt * xy.y + n * sqrt(max(1.0 - dot(xy, xy), 0.0)));
            if (!any(isnan(m))) ns = m;
        }
    }
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) {
        n = -n;
        ns = -ns;
    }
    // A shading normal facing away from the ray falls back to the geometric one.
    if (dot(ns, gl_WorldRayDirectionEXT) > -0.02) ns = n;

    float sky = (float((a.light >> 16) & 0xFFFFu) * w.x + float((b.light >> 16) & 0xFFFFu) * w.y
            + float((c.light >> 16) & 0xFFFFu) * w.z) / 240.0;
    float block = (float(a.light & 0xFFFFu) * w.x + float(b.light & 0xFFFFu) * w.y
            + float(c.light & 0xFFFFu) * w.z) / 240.0;

    payload.albedo = albedo;
    payload.t = gl_HitTEXT;
    payload.normal = n;
    payload.shading = ns;
    payload.skyLight = clamp(sky, 0.0, 1.0);
    payload.blockLight = clamp(block, 0.0, 1.0);
    // Vertex colors carry face shading and smooth-lighting occlusion (grey) besides biome
    // tints (water, grass, leaves): only a hue counts as tinted.
    payload.tinted = max(max(tint.r, tint.g), tint.b) - min(min(tint.r, tint.g), tint.b) > 0.08 ? 1.0 : 0.0;
    payload.material = textureLod(materialAtlas, uv, 0.0);
}
