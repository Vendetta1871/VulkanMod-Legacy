#version 450
// LOD terrain fragment stage (AUDIT 5.15): the face tile of the model in the LOD atlas,
// repeated per voxel; biome tint, baked light through the vanilla lightmap, 1.12 face
// shading, fog and the fade band. Fragments inside a section vanilla has built are dropped.
#extension GL_GOOGLE_include_directive : require
#include "lod_common.glsl"

layout(set = 0, binding = 5) uniform sampler2D atlas;
layout(set = 0, binding = 6) uniform sampler2D lightmap;
layout(std430, set = 0, binding = 7) readonly buffer Cover { uint coverBits[]; };

layout(location = 0) in vec3 vRel;
layout(location = 1) in vec2 vUv;
layout(location = 2) flat in uvec4 vInfo;

layout(location = 0) out vec4 fragColor;

const vec3 NORMALS[6] = vec3[](vec3(0, -1, 0), vec3(0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1), vec3(-1, 0, 0), vec3(1, 0, 0));
const float SHADE[6] = float[](0.5, 1.0, 0.8, 0.8, 0.6, 0.6);

bool covered(vec3 rel, int face) {
    if (cover.w == 0) return false;
    // A point just inside the voxel the face belongs to.
    vec3 p = camFrac.xyz + rel - NORMALS[face] * 0.01;
    ivec3 b = camBlock.xyz + ivec3(floor(p));
    if (b.y < 0 || b.y > 255) return false;
    ivec3 s = ivec3(b.x >> 4, b.y >> 4, b.z >> 4);
    int gx = s.x - cover.x, gz = s.z - cover.z, n = cover.y;
    if (gx < 0 || gz < 0 || gx >= n || gz >= n) return false;
    int bit = (gz * n + gx) * 16 + s.y;
    return (coverBits[bit >> 5] & (1u << (bit & 31))) != 0u;
}

void main() {
    int face = int(vInfo.y & 15u);
    if (covered(vRel, face)) {
        // In the edge band vanilla drops a share of its pixels (terrain.fsh): show those.
        float t = edge.y > edge.x ? smoothstep(edge.x, edge.y, length(vRel.xz)) : 0.0;
        if (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) >= t) discard;
    }
    uint model = vInfo.x;
    uint fd = models[model].faceData[face];

    vec2 pix = fract(vUv) * 16.0;
    ivec2 pi = clamp(ivec2(pix), ivec2(0), ivec2(15));
    if (pi.x < int(fd & 15u) || pi.y < int((fd >> 4) & 15u) || pi.x > int((fd >> 8) & 15u) || pi.y > int((fd >> 12) & 15u)) discard;

    vec2 atlasSize = lodFade.zw;
    vec2 cell = vec2(float(model & 255u) * 48.0 + float(face % 3) * 16.0, float(model >> 8) * 32.0 + float(face / 3) * 16.0);
    vec2 uv = (cell + vec2(pi) + 0.5) / atlasSize;
    // Analytic derivatives of the unwrapped coordinate: no mip seam where fract() wraps.
    vec2 gx = dFdx(vUv) * 16.0 / atlasSize, gy = dFdy(vUv) * 16.0 / atlasSize;
    vec4 color = textureGrad(atlas, uv, gx, gy);
    float alpha0 = textureLod(atlas, uv, 0.0).a;
#ifdef LOD_TRANSLUCENT
    const bool translucent = true;
#else
    const bool translucent = false;
#endif
    if (translucent) {
        if (alpha0 <= 0.0) discard;
    } else {
        if (((fd >> 22) & 1u) != 0u && alpha0 <= 0.1) discard;
        color.a = 1.0;
    }

    uint tintMode = (fd >> 24) & 3u;
    uint t = vInfo.w;
    if (t != 0u && tintMode != 0u) {
        vec3 tint = vec3(float((t >> 16) & 255u), float((t >> 8) & 255u), float(t & 255u)) / 255.0;
        bool grey = abs(color.r - color.g) < 0.02 && abs(color.g - color.b) < 0.02;
        if (tintMode == 2u || grey) color.rgb *= tint;
    }

    uint light = vInfo.z;
    vec2 lm = (vec2(float(light >> 4), float(light & 15u)) + 0.5) / 16.0;
    color.rgb *= texture(lightmap, lm).rgb;
    if ((models[model].flags & 8u) != 0u) color.rgb *= SHADE[face];

    float dist = length(vRel);
    int mode = int(fog.z);
    if (mode == 1) {
        float f = clamp((fog.y - dist) / max(fog.y - fog.x, 1e-4), 0.0, 1.0);
        color.rgb = mix(fogColor.rgb, color.rgb, f);
    } else if (mode == 2) {
        color.rgb = mix(fogColor.rgb, color.rgb, clamp(exp(-fog.w * dist), 0.0, 1.0));
    } else if (mode == 3) {
        float d = fog.w * dist;
        color.rgb = mix(fogColor.rgb, color.rgb, clamp(exp(-d * d), 0.0, 1.0));
    }
    if (lodFade.y > lodFade.x) {
        float fade = smoothstep(lodFade.x, lodFade.y, length(vRel.xz));
        color.rgb = mix(color.rgb, fogColor.rgb, fade);
    }
    fragColor = color;
}
