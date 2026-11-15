// Block lights of the RT set (RtLights) and their unshadowed contribution. Include after the
// push constants: uses pc.cam (xyz: camera - light origin, w: light count), pc.params.w (grid
// mask) and pc.restir.z (dynamic light count).

// Per light two entries: xyz center relative to the origin, w level; then rgb color.
layout(set = 0, binding = 6, scalar) readonly buffer Lights { vec4 lights[]; };
// Dynamic lights (held / dropped light sources), same layout; indices from DYNAMIC on.
layout(set = 0, binding = 23, scalar) readonly buffer DynamicLights { vec4 dynamicLights[]; };
layout(set = 0, binding = 7, scalar) readonly buffer Grid { ivec4 cells[]; };          // cell xyz, start | count << 24
layout(set = 0, binding = 8, scalar) readonly buffer LightIndex { int lightIndex[]; };
const int DYNAMIC = 1 << 29;
const int CELL = 8;

vec4 lightAt(int li) { return li >= DYNAMIC ? dynamicLights[(li - DYNAMIC) * 2] : lights[li * 2]; }
vec3 lightColor(int li) { return li >= DYNAMIC ? dynamicLights[(li - DYNAMIC) * 2 + 1].rgb : lights[li * 2 + 1].rgb; }

int findCell(ivec3 c) {
    int mask = int(pc.params.w);
    int slot = (c.x * 73856093 ^ c.y * 19349663 ^ c.z * 83492791) & mask;
    for (int i = 0; i <= mask; i++) {
        ivec4 e = cells[slot];
        if (e.x == int(0x80000000)) return -1;
        if (e.xyz == c) return slot;
        slot = (slot + 1) & mask;
    }
    return -1;
}

// Unshadowed block light from one emitter, scene units (RtLighting.SCENE_SCALE): a small
// sphere light with physical inverse-square falloff and Lambert's cosine, as NVIDIA's RTX
// Remix for Minecraft lights a torch (radius 0.08, radiance 1575 at level 14: an intensity of
// 31.7 per unit color); the vanilla level scales it. A soft window takes it to zero at the
// light's vanilla reach (its level, in blocks), where the light grid stops listing it.
const float LIGHT_INTENSITY = 3.17, LIGHT_RADIUS = 0.3;
float lightTarget(vec4 l, vec3 pos, vec3 n, out vec3 toLight, out float dist) {
    toLight = l.xyz - pc.cam.xyz - pos;
    dist = length(toLight);
    float reach = max(l.w, 1.0);
    if (dist >= reach) return 0.0;
    float ndl = dot(n, toLight) / max(dist, 1e-4);
    if (ndl <= 0.0) return 0.0;
    float x = dist / reach;
    float window = 1.0 - x * x * x * x;
    return l.w / 14.0 * LIGHT_INTENSITY / max(dist * dist, LIGHT_RADIUS * LIGHT_RADIUS) * window * window * ndl;
}

// Distance along the ray at which it enters the light's own block (stops the shadow ray there).
float blockEntry(vec3 origin, vec3 dir, vec3 center) {
    vec3 inv = 1.0 / dir;
    vec3 t0 = (center - 0.5 - origin) * inv;
    vec3 t1 = (center + 0.5 - origin) * inv;
    vec3 tmin = min(t0, t1);
    return max(max(tmin.x, tmin.y), tmin.z);
}
