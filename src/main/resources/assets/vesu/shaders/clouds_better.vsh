#version 450

// Better clouds (BetterClouds): one instance per cloud cube, 36 vertices each. The cube
// corner comes from gl_VertexIndex; the instance attribute is the cube center relative to
// the displayed generation task's block origin, y = height above the cloud base. The
// modelview inside MVP is the camera rotation followed by translate(renderOrigin), so
// P + renderOrigin is the camera-relative cube center.

layout(location = 0) in vec3 Instance;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    // [0] sizeXZ, sizeY, shader time T (s), colorTransitionEnd
    // [1] renderOrigin.xyz, Dr (generation distance - chunkSize / 2)
    // [2] camera x, camera z, H (yRange + sizeY), scaleFalloffMin
    // [3] windEffectFactor, windSpeedFactor, fogDensityFactor, fog end E
    // [4] world curvature C (0 = off), near fade (0/1), unused, unused
    vec4 BcVert[5];
};

// Noise texture: only the red channel is used (bilinear, repeat).
layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) out float fogFade;
layout(location = 1) out float tintT;
layout(location = 2) out vec3 lightDir;

// Two triangles per face; corner numbering 0 (-,-,-) 1 (+,-,-) 2 (+,+,-) 3 (-,+,-)
// 4 (-,-,+) 5 (+,-,+) 6 (+,+,+) 7 (-,+,+). Culling is off, the winding is irrelevant.
const int INDICES[36] = int[36](
        2, 1, 0, 0, 3, 2,   // -Z
        4, 5, 6, 6, 7, 4,   // +Z
        3, 0, 4, 4, 7, 3,   // -X
        6, 5, 1, 1, 2, 6,   // +X
        1, 5, 4, 4, 0, 1,   // -Y
        6, 2, 3, 3, 7, 6);  // +Y

vec3 corner(int i) {
    return vec3((i == 1 || i == 2 || i == 5 || i == 6) ? 0.5 : -0.5,
                (i == 2 || i == 3 || i == 6 || i == 7) ? 0.5 : -0.5,
                i >= 4 ? 0.5 : -0.5);
}

void main() {
    vec3 L = corner(INDICES[gl_VertexIndex]);
    vec3 P = Instance;
    vec3 origin = BcVert[1].xyz;
    vec3 c = P + origin;

    // 1. Distance scale falloff (quadratic, unclamped) and height compression.
    float Dr = BcVert[1].w;
    float sf = mix(1.0, BcVert[2].w, dot(c.xz, c.xz) / (Dr * Dr));
    vec3 center = vec3(P.x, P.y * sf, P.z);

    // 2. Wind wobble: a scrolling noise texture in world XZ modulates the cube size.
    vec2 w = c.xz + BcVert[2].xy;
    float T = BcVert[0].z;
    float windSpeed = BcVert[3].y;
    float big = textureLod(Sampler0, w / 4000.0 + vec2(windSpeed * T / 800.0), 0.0).r;
    float small = textureLod(Sampler0, w.yx / 1000.0 + vec2(windSpeed * T / 200.0), 0.0).r * 1.8 - 0.9;
    float wave = mix(mix(big, 1.0, max(small, 0.0)), 0.0, max(-small, 0.0));
    float H = BcVert[2].z;
    float damping = 1.0 - smoothstep(0.0, H / 4.0, P.y + 0.5);
    float dyn = mix(1.0, wave, damping * BcVert[3].x);

    // 3./4. Scale and vertex position (task-relative), camera-relative copy.
    vec3 S = vec3(BcVert[0].x, BcVert[0].y, BcVert[0].x) * dyn * sf;
    vec3 V = S * L + center;
    vec3 v = V + origin;

    // 5. World curvature (off by default).
    float C = BcVert[4].x;
    if (C != 0.0) {
        V.y -= dot(v, v) / C;
    }

    // 6. Fog fade.
    float d = length(v);
    float nearF = BcVert[4].y > 0.5 ? clamp(d / 40.0, 0.0, 1.0) : 1.0;
    float farF = clamp(exp(-d / BcVert[3].w * BcVert[3].z), 0.0, 1.0);
    fogFade = nearF * nearF * farF * farF;

    // 7. Bottom-to-top colour interpolation, from the unscaled height.
    tintT = clamp((S.y * L.y + P.y + 0.3 * H) / (BcVert[0].w * H), 0.0, 1.0);

    // 8. Light sample direction: the camera-to-vertex vector.
    lightDir = v;

    gl_Position = MVP * vec4(V, 1.0);
}
