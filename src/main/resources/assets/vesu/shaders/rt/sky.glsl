// Sky and clouds of the RT composites (rt_composite.comp, rt_pt_resolve.comp). Include after
// the push constants: uses pc.sun (xyz: towards the sun, w: sky source radiance), pc.direct,
// pc.lightDir, pc.skyMean and pc.clouds of their shared layout.
#ifndef RT_SKY_GLSL
#define RT_SKY_GLSL

// Clouds: vanilla's layer (4 blocks thick, 12-block cells) as boxes over a blob pattern of
// value noise that repeats every 256 cells, lit like a white Lambertian surface by the direct
// light and the sky, fading into the sky with distance. rgb: radiance, a: coverage.
float cloudHash(ivec2 c) {
    uint h = uint(c.x & 255) * 73856093u ^ uint(c.y & 255) * 19349663u;
    h ^= h >> 13; h *= 1274126177u; h ^= h >> 16;
    return float(h & 0xFFFFu) / 65535.0;
}
float cloudNoise(vec2 x, float period) {
    vec2 i = floor(x), f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    int pi = int(period);
    ivec2 c = ivec2(i);
    float a = cloudHash((c % pi) * 7), b = cloudHash(((c + ivec2(1, 0)) % pi) * 7);
    float d = cloudHash(((c + ivec2(0, 1)) % pi) * 7), e = cloudHash(((c + ivec2(1, 1)) % pi) * 7);
    return mix(mix(a, b, f.x), mix(d, e, f.x), f.y);
}
bool cloudCell(ivec2 c) {
    ivec2 w = c & 255;
    vec2 q = vec2(w);
    float v = cloudNoise(q / 8.0, 32.0) * 0.65 + cloudNoise(q / 3.0, 256.0 / 3.0) * 0.35;
    return v > 0.56;
}
vec4 traceClouds(vec3 dir, float maxT) {
    if (pc.clouds.w <= 0.0) return vec4(0.0);
    float y0 = pc.clouds.y, y1 = y0 + 4.0;
    float t0, t1;
    if (abs(dir.y) < 1e-5) {
        if (y0 > 0.0 || y1 < 0.0) return vec4(0.0);
        t0 = 0.0; t1 = 1e9;
    } else {
        float ta = y0 / dir.y, tb = y1 / dir.y;
        t0 = max(min(ta, tb), 0.0);
        t1 = max(ta, tb);
    }
    t1 = min(t1, min(maxT, 2500.0));
    if (t1 <= t0) return vec4(0.0);
    vec2 o = pc.clouds.xz + dir.xz * t0;
    ivec2 cell = ivec2(floor(o / 12.0));
    vec2 d = dir.xz;
    vec2 stepv = vec2(d.x >= 0.0 ? 1.0 : -1.0, d.y >= 0.0 ? 1.0 : -1.0);
    vec2 inv = vec2(abs(d.x) > 1e-6 ? 1.0 / d.x : 1e9, abs(d.y) > 1e-6 ? 1.0 / d.y : 1e9);
    vec2 tMax = t0 + ((vec2(cell) + max(stepv, 0.0)) * 12.0 - o) * inv;
    vec2 tDelta = abs(12.0 * inv);
    vec3 n = t0 > 0.0 ? vec3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0) : -dir;
    float t = t0;
    for (int i = 0; i < 128; i++) {
        if (cloudCell(cell)) {
            // Clouds scatter light through: unlit faces still get a share of the direct light.
            float ndl = dot(n, pc.lightDir.xyz);
            vec3 e = pc.direct.rgb * (max(ndl, 0.0) * 0.75 + 0.25)
                    + PI * pc.skyMean.rgb * (n.y > 0.5 ? 1.1 : n.y < -0.5 ? 0.7 : 0.9);
            vec3 c = 0.9 / PI * e;
            float far = smoothstep(500.0, 2000.0, t);
            return vec4(c, 1.0 - far);
        }
        if (tMax.x < tMax.y) {
            t = tMax.x; tMax.x += tDelta.x; cell.x += int(stepv.x); n = vec3(-stepv.x, 0.0, 0.0);
        } else {
            t = tMax.y; tMax.y += tDelta.y; cell.y += int(stepv.y); n = vec3(0.0, 0.0, -stepv.y);
        }
        if (t >= t1) break;
    }
    return vec4(0.0);
}

vec3 skyRadiance(vec3 dir) {
    vec3 sky = atmosphere(dir, pc.sun.xyz, pc.sun.w, pc.skyMean.rgb);
    // Sun and moon: the vanilla sprites' bright cores as emitters (radiance 8 and 0.2 in Remix,
    // here with the atmosphere's transmittance), squares 8.5 and 5.7 degrees across.
    vec3 s = normalize(pc.sun.xyz);
    vec3 a = normalize(cross(s, vec3(0.0, 0.0, 1.0)));
    vec3 b = cross(a, s);
    float cs = dot(dir, s);
    if (cs > 0.0) {
        vec2 q = abs(vec2(dot(dir, a), dot(dir, b))) / cs;
        if (max(q.x, q.y) < 0.075) sky += 0.8 * atmViewTransmittance(s) * smoothstep(0.075, 0.06, max(q.x, q.y));
    } else {
        vec2 q = abs(vec2(dot(dir, a), dot(dir, b))) / -cs;
        if (max(q.x, q.y) < 0.05) sky += vec3(0.02) * smoothstep(0.05, 0.04, max(q.x, q.y));
    }
    return sky;
}

#endif
