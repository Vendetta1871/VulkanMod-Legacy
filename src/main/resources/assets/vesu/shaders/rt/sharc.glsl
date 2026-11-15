// Radiance cache in a spatial hash grid, after NVIDIA's SHaRC (RTX Remix's radiance cache):
// voxels keyed by position (size growing with the distance to the camera, a constant
// footprint on screen) and the face's dominant axis, holding the radiance leaving the
// surfaces in them (scene units: emission plus all bounces of light). sharc_update.rgen
// traces short paths from a sparse set of pixels and adds the radiance of every vertex
// (SHARC_WRITE); sharc_resolve.comp blends it into the voxels and evicts stale ones; the
// lighting pass ends its indirect rays in the cache (sharcQuery).
//
// Bindings of the RT set (RtSharc): 37 keys (checksums, 0 = free), 38 resolved voxels
// (rgb radiance, w: accumulated sample count), 39 this frame's sums (rgb fixed point,
// w: sample count | frames since the last sample << 16). Include after the push constants.

#ifdef SHARC_WRITE
layout(set = 0, binding = 37) buffer SharcKeys { uint sharcKeys[]; };
layout(set = 0, binding = 39) buffer SharcAccum { uvec4 sharcAccum[]; };
#else
layout(set = 0, binding = 37) readonly buffer SharcKeys { uint sharcKeys[]; };
#endif
layout(set = 0, binding = 38) readonly buffer SharcVoxels { vec4 sharcVoxels[]; };

const int SHARC_BUCKET = 8;
// Voxel edge: the distance to the camera over 32, a power of two from 1/4 to 8 blocks.
const float SHARC_RATIO = 1.0 / 32.0;
const int SHARC_LEVEL_MIN = -2, SHARC_LEVEL_MAX = 3;
// Fixed point of the sums; radiance above SHARC_CLAMP is clipped (fireflies).
const float SHARC_SCALE = 4096.0, SHARC_CLAMP = 16.0;

uint sharcHash(uint x) {
    x ^= x >> 16; x *= 0x7feb352du;
    x ^= x >> 15; x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

// Slot hash and checksum (never 0) of the voxel holding pos (camera-relative) on a face
// with normal n. pc.cam.xyz anchors the grid to the world (RtLights' origin).
void sharcKey(vec3 pos, vec3 n, out uint slotHash, out uint checksum) {
    int level = clamp(int(floor(log2(max(length(pos), 1.0) * SHARC_RATIO))), SHARC_LEVEL_MIN, SHARC_LEVEL_MAX);
    // Just inside the surface: a point on a block face does not flicker between two voxels.
    ivec3 c = ivec3(floor((pos + pc.cam.xyz - n * 0.01) * exp2(-float(level))));
    vec3 a = abs(n);
    uint axis = a.x > a.y && a.x > a.z ? (n.x > 0.0 ? 0u : 1u) : a.y > a.z ? (n.y > 0.0 ? 2u : 3u) : (n.z > 0.0 ? 4u : 5u);
    uint tag = uint(level - SHARC_LEVEL_MIN) * 8u + axis;
    uint h = sharcHash(uint(c.x) + sharcHash(uint(c.y) + sharcHash(uint(c.z) + sharcHash(tag))));
    slotHash = h;
    checksum = sharcHash(h ^ 0x9e3779b9u ^ (uint(c.x) * 0x632be5abu)) | 1u;
}

uint sharcBase(uint slotHash) {
    return (slotHash % uint(sharcKeys.length())) & ~uint(SHARC_BUCKET - 1);
}

// Cached radiance of the voxel at pos (w: sample count, 0 when nothing is cached).
vec4 sharcQuery(vec3 pos, vec3 n) {
    if (sharcKeys.length() < SHARC_BUCKET || any(isnan(pos))) return vec4(0.0);
    uint h, chk;
    sharcKey(pos, n, h, chk);
    uint base = sharcBase(h);
    for (int i = 0; i < SHARC_BUCKET; i++) {
        if (sharcKeys[base + uint(i)] == chk) return sharcVoxels[base + uint(i)];
    }
    return vec4(0.0);
}

#ifdef SHARC_WRITE
// The voxel's slot, claimed when new; -1 when its bucket is full.
int sharcInsert(vec3 pos, vec3 n) {
    if (sharcKeys.length() < SHARC_BUCKET || any(isnan(pos))) return -1;
    uint h, chk;
    sharcKey(pos, n, h, chk);
    uint base = sharcBase(h);
    for (int i = 0; i < SHARC_BUCKET; i++) {
        uint prev = atomicCompSwap(sharcKeys[base + uint(i)], 0u, chk);
        if (prev == 0u || prev == chk) return int(base) + i;
    }
    return -1;
}

// Adds one radiance sample; u: a uniform random number (unbiased rounding of the fixed point,
// which keeps the faint light of night and caves).
void sharcAdd(int slot, vec3 radiance, float u) {
    if (slot < 0) return;
    uvec3 v = uvec3(floor(clamp(radiance, vec3(0.0), vec3(SHARC_CLAMP)) * SHARC_SCALE + u));
    atomicAdd(sharcAccum[slot].x, v.x);
    atomicAdd(sharcAccum[slot].y, v.y);
    atomicAdd(sharcAccum[slot].z, v.z);
    atomicAdd(sharcAccum[slot].w, 1u);
}
#endif
