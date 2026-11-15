// Dynamic Diffuse Global Illumination (Majercik, Guertin, Nowrouzezahrai, McGuire:
// "Dynamic Diffuse Global Illumination with Ray-Traced Irradiance Fields", JCGT 8(2), 2019),
// with probe relocation and classification after Majercik, Marrs, Spjut, McGuire: "Scaling
// Probe-Based Real-Time Dynamic Global Illumination for Production", JCGT 10(2), 2021.
//
// Probes sit on camera-centred grids (cascades): cascade c has spacing SPACING * 2^c and the
// same probe counts. A probe keeps its storage slot while the grid scrolls (slot = world grid
// coordinate mod count); a slot taken over by a new probe is reset (ddgi_prepare.comp).
// Irradiance and visibility live in octahedral atlases, one tile per slot:
// tile x = slot.x + slot.z * count.x, tile y = slot.y + cascade * count.y. The linear probe
// index is tile x + tile y * (count.x * count.z).
//
// Units follow the lighting pass: radiance in the lightmap's brightness, the sky at luminance
// pc.camDelta.w. The irradiance atlas stores cosine-weighted mean radiance (irradiance / pi),
// gamma-encoded for blending.

#define DDGI_MAX_CASCADES 8
#define DDGI_IRRADIANCE_TEXELS 8   // interior texels per tile side (one border texel around)
#define DDGI_DISTANCE_TEXELS 16
#define DDGI_FIXED_RAYS 16         // unrotated rays for relocation and classification only
#define DDGI_GAMMA 5.0
#define DDGI_ACTIVE 0.0
#define DDGI_INACTIVE 1.0

struct DdgiCascade {
    vec4 origin;   // xyz: camera-relative position of the grid's first probe, w: spacing
    ivec4 base;    // xyz: world grid coordinate of the first probe, w: unused
};

layout(set = DDGI_SET, binding = DDGI_VOLUME_BINDING, scalar) readonly buffer DdgiVolume {
    ivec4 counts;      // xyz: probes per axis, w: cascades
    ivec4 rays;        // x: blended rays per probe, y: fixed rays, z: rays per probe, w: frame
    mat4 rotation;     // this frame's random rotation of the blended rays
    vec4 params;       // x: hysteresis, y: 1 resets every probe, z: min frontface distance (spacings), w: unused
    DdgiCascade cascades[DDGI_MAX_CASCADES];
} ddgi;

struct DdgiProbe {
    vec4 offset;   // xyz: relocation offset (blocks), w: DDGI_ACTIVE / DDGI_INACTIVE
    ivec4 grid;    // xyz: world grid coordinate the slot holds, w: 1 when reset this frame
};

layout(set = DDGI_SET, binding = DDGI_PROBE_BINDING, scalar) DDGI_PROBE_ACCESS buffer DdgiProbes {
    DdgiProbe probes[];
} ddgiProbes;

int ddgiProbesPerCascade() { return ddgi.counts.x * ddgi.counts.y * ddgi.counts.z; }
int ddgiProbeCount() { return ddgiProbesPerCascade() * ddgi.counts.w; }

// Non-negative remainder. GLSL leaves % undefined for negative operands (NVIDIA returned
// -12 % 24 = 4), so negatives are folded explicitly.
int imod(int a, int b) { return a >= 0 ? a % b : b - 1 - (-a - 1) % b; }
ivec3 imod3(ivec3 a, ivec3 b) { return ivec3(imod(a.x, b.x), imod(a.y, b.y), imod(a.z, b.z)); }

// Tile (atlas cell) of a linear probe index, and back.
ivec2 ddgiTile(int probe) {
    int row = ddgi.counts.x * ddgi.counts.z;
    return ivec2(probe % row, probe / row);
}

int ddgiCascadeOf(int probe) { return ddgiTile(probe).y / ddgi.counts.y; }

ivec3 ddgiSlotOf(int probe) {
    ivec2 t = ddgiTile(probe);
    return ivec3(t.x % ddgi.counts.x, t.y % ddgi.counts.y, t.x / ddgi.counts.x);
}

int ddgiProbeIndex(ivec3 slot, int cascade) {
    int tx = slot.x + slot.z * ddgi.counts.x;
    int ty = slot.y + cascade * ddgi.counts.y;
    return tx + ty * ddgi.counts.x * ddgi.counts.z;
}

// World grid coordinate a slot holds this frame: the one in [base, base + count).
ivec3 ddgiGridOf(ivec3 slot, int cascade) {
    ivec3 base = ddgi.cascades[cascade].base.xyz;
    return base + imod3(slot - base, ddgi.counts.xyz);
}

// Camera-relative position of a grid coordinate, without relocation.
vec3 ddgiGridPosition(ivec3 grid, int cascade) {
    DdgiCascade c = ddgi.cascades[cascade];
    return c.origin.xyz + vec3(grid - c.base.xyz) * c.origin.w;
}

vec3 ddgiProbePosition(int probe) {
    int cascade = ddgiCascadeOf(probe);
    return ddgiGridPosition(ddgiGridOf(ddgiSlotOf(probe), cascade), cascade) + ddgiProbes.probes[probe].offset.xyz;
}

// Octahedral mapping of the unit sphere onto [-1, 1]^2 (Cigolle et al. 2014).
vec2 ddgiSignNotZero(vec2 v) { return vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0); }

vec2 ddgiOctEncode(vec3 d) {
    vec2 p = d.xy / (abs(d.x) + abs(d.y) + abs(d.z));
    return d.z < 0.0 ? (1.0 - abs(p.yx)) * ddgiSignNotZero(p) : p;
}

vec3 ddgiOctDecode(vec2 e) {
    vec3 d = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (d.z < 0.0) d.xy = (1.0 - abs(d.yx)) * ddgiSignNotZero(d.xy);
    return normalize(d);
}

// Direction of texel t of an n x n tile interior.
vec3 ddgiTexelDirection(ivec2 t, int n) {
    return ddgiOctDecode((vec2(t) + 0.5) / float(n) * 2.0 - 1.0);
}

// i-th of n points of a spherical Fibonacci set (Keinert et al. 2015).
vec3 ddgiFibonacci(int i, int n) {
    const float GOLDEN = 1.6180339887;
    float phi = 6.2831853 * fract(float(i) / GOLDEN);
    float cosTheta = 1.0 - (2.0 * float(i) + 1.0) / float(n);
    float sinTheta = sqrt(clamp(1.0 - cosTheta * cosTheta, 0.0, 1.0));
    return vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
}

// Direction of ray r of a probe: the fixed set first, then the rotated blended set.
vec3 ddgiRayDirection(int r) {
    int fixedRays = ddgi.rays.y;
    if (r < fixedRays) return ddgiFibonacci(r, fixedRays);
    return mat3(ddgi.rotation) * ddgiFibonacci(r - fixedRays, ddgi.rays.x);
}

// Atlas uv of an octahedral coordinate in a probe's tile (bilinear across the border).
vec2 ddgiAtlasUv(int probe, vec2 oct, int texels, vec2 atlasSize) {
    vec2 tile = vec2(ddgiTile(probe)) * float(texels + 2);
    return (tile + 1.0 + (oct * 0.5 + 0.5) * float(texels)) / atlasSize;
}

#ifdef DDGI_SAMPLING
layout(set = DDGI_SET, binding = DDGI_IRRADIANCE_BINDING) uniform sampler2D ddgiIrradianceAtlas;
layout(set = DDGI_SET, binding = DDGI_DISTANCE_BINDING) uniform sampler2D ddgiDistanceAtlas;

// Weight of a cascade at a camera-relative point: 1 inside the region its probes enclose,
// falling to 0 over the outermost cell.
float ddgiCascadeWeight(vec3 pos, int cascade) {
    DdgiCascade c = ddgi.cascades[cascade];
    vec3 local = (pos - c.origin.xyz) / c.origin.w;          // in probe cells from the first probe
    vec3 extent = vec3(ddgi.counts.xyz - 1);
    vec3 outside = max(vec3(1.0) - local, local - (extent - vec3(1.0)));   // > 0 within the outer cell
    vec3 w = clamp(1.0 - outside, 0.0, 1.0);
    return w.x * w.y * w.z;
}

// Irradiance of one cascade at a surface point (paper section 4.3): the eight probes of the
// enclosing cell, weighted trilinearly, by a smooth backface term and by Chebyshev visibility
// from their distance moments. Returns the weighted sum in .rgb (square-root space) and the
// weight in .a.
vec4 ddgiSampleCascade(vec3 pos, vec3 n, vec3 toCamera, int cascade) {
    DdgiCascade c = ddgi.cascades[cascade];
    float spacing = c.origin.w;
    // Self-shadowing bias (paper section 4.3): along the normal and towards the camera.
    vec3 biased = pos + (n * 0.2 + toCamera * 0.8) * (0.75 * spacing) * 0.3;
    vec3 local = (biased - c.origin.xyz) / spacing;
    ivec3 baseCell = clamp(ivec3(floor(local)), ivec3(0), ddgi.counts.xyz - 2);
    vec3 alpha = clamp(local - vec3(baseCell), 0.0, 1.0);
    vec2 irrSize = vec2(textureSize(ddgiIrradianceAtlas, 0));
    vec2 distSize = vec2(textureSize(ddgiDistanceAtlas, 0));

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 corner = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        ivec3 grid = c.base.xyz + baseCell + corner;
        int probe = ddgiProbeIndex(imod3(grid, ddgi.counts.xyz), cascade);
        DdgiProbe pr = ddgiProbes.probes[probe];
        if (pr.offset.w != DDGI_ACTIVE) continue;
        vec3 probePos = ddgiGridPosition(grid, cascade) + pr.offset.xyz;

        vec3 tri = mix(vec3(1.0) - alpha, alpha, vec3(corner));
        float trilinear = max(tri.x * tri.y * tri.z, 1e-3);

        // Smooth backface: probes behind the surface fade without a hard cut.
        vec3 toProbe = normalize(probePos - pos);
        float wrap = (dot(toProbe, n) + 1.0) * 0.5;
        float w = wrap * wrap + 0.2;

        // Visibility: Chebyshev's bound from the mean and mean square distance the probe
        // saw towards the biased point.
        vec3 fromProbe = biased - probePos;
        float r = length(fromProbe);
        vec2 moments = textureLod(ddgiDistanceAtlas,
                ddgiAtlasUv(probe, ddgiOctEncode(fromProbe / max(r, 1e-4)), DDGI_DISTANCE_TEXELS, distSize), 0.0).rg;
        if (r > moments.x) {
            float variance = abs(moments.x * moments.x - moments.y);
            float d = r - moments.x;
            float cheb = variance / (variance + d * d);
            w *= max(cheb * cheb * cheb, 0.05);
        }
        w = max(w, 1e-6);
        // Crush weights that are already small: removes faint light leaks.
        const float CRUSH = 0.2;
        if (w < CRUSH) w *= w * w / (CRUSH * CRUSH);
        w *= trilinear;

        vec3 irr = textureLod(ddgiIrradianceAtlas,
                ddgiAtlasUv(probe, ddgiOctEncode(n), DDGI_IRRADIANCE_TEXELS, irrSize), 0.0).rgb;
        // Stored with 1 / gamma; interpolated in square-root space (paper), squared at the end.
        sum += w * pow(max(irr, vec3(0.0)), vec3(DDGI_GAMMA * 0.5));
        wsum += w;
    }
    return vec4(sum, wsum);
}

vec3 ddgiResolve(vec4 s) {
    vec3 v = s.rgb / s.a;
    return v * v;
}

// How far a cascade sample can be trusted: the sum of its probe weights (trilinear times
// backface times visibility) is near 1 where the probes around the point see it, and falls
// towards 0 where most of them are inactive, buried or occluded (geometry thinner than the
// probe spacing: a one-block crevice). Normalizing such a sum lets one barely visible probe
// decide the light, so callers blend towards a fallback by this confidence.
float ddgiConfidence(float wsum) { return smoothstep(0.03, 0.3, wsum); }

// Cosine-weighted mean incoming radiance at a camera-relative surface point (rgb) and its
// confidence (a, 0..1); a < 0 where no cascade covers the point. toCamera: unit vector
// from the point towards the eye.
vec4 ddgiIrradianceConf(vec3 pos, vec3 n, vec3 toCamera) {
    for (int c = 0; c < ddgi.counts.w; c++) {
        float w = ddgiCascadeWeight(pos, c);
        if (w <= 0.0) continue;
        vec4 fine = ddgiSampleCascade(pos, n, toCamera, c);
        vec4 fineV = fine.a > 0.0 ? vec4(ddgiResolve(fine), ddgiConfidence(fine.a)) : vec4(0.0);
        if (w >= 1.0 || c + 1 >= ddgi.counts.w) return fineV;
        // The outer cell blends into the next, coarser cascade.
        vec4 coarse = ddgiSampleCascade(pos, n, toCamera, c + 1);
        vec4 coarseV = coarse.a > 0.0 ? vec4(ddgiResolve(coarse), ddgiConfidence(coarse.a)) : vec4(0.0);
        vec4 m = mix(coarseV, fineV, w);
        // Irradiance weighted by confidence, so an untrusted sample does not dilute a trusted one.
        float wc = mix(coarseV.a, fineV.a, w);
        vec3 irr = wc > 0.0 ? (coarseV.rgb * coarseV.a * (1.0 - w) + fineV.rgb * fineV.a * w) / wc : vec3(0.0);
        return vec4(irr, m.a);
    }
    return vec4(0.0, 0.0, 0.0, -1.0);
}

// As above without the confidence: negative where nothing covers the point or no probe is usable.
vec3 ddgiIrradiance(vec3 pos, vec3 n, vec3 toCamera) {
    vec4 v = ddgiIrradianceConf(pos, n, toCamera);
    return v.a > 0.0 ? v.rgb : vec3(-1.0);
}
#endif // DDGI_SAMPLING
