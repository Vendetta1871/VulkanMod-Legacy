#version 450
// Host source of the fragment stage of the pack programs that draw distant terrain
// (AUDIT 5.16). The pack shades from its vertex outputs. Through DH programs distant
// terrain and water keep their fragments where vanilla has built, as Distant Horizons does:
// the pack drops distant water over vanilla terrain itself (depthtex0) and fades vanilla
// terrain and water near its edge for DH to fill; dropping it there left that band
// without water. Through the pack's terrain programs (the scene depth) the fragments in a
// chunk section vanilla has built are dropped after the pack's main.

layout(std140, binding = 0) uniform LodPackFrame {
    mat4 MVP;
    mat4 ModelViewMat;
    ivec4 LodCamBlock;
    vec4 LodCamFrac;
    mat4 LodInvMVP;
    ivec4 LodCover;     // x, z: chunk of the coverage grid's corner, y: grid size in chunks, w: 1 when valid;
                        // w: 2 in the shadow map, y: the vanilla distance in blocks
    vec4 LodScreen;     // x, y: size in pixels, z: -1 when the viewport flips y, w: 1 drops covered opaque too
};
layout(std430, binding = 5) readonly buffer LodCoverBits { uint lodCoverBits[]; };

//vkm-host-prelude-begin
#define VKM_LOD_DISCARD
layout(location = 31) in vec3 vkmLodRel;

void vkm_lodDiscard() {
    // Only when distant terrain shares the scene depth (the pack's terrain programs), where
    // coarse terrain and water would cover vanilla.
    if (LodCover.w == 2) {
        // The shadow map: vanilla terrain is in it within the vanilla distance.
        if (dot(vkmLodRel.xz, vkmLodRel.xz) < float(LodCover.y) * float(LodCover.y)) discard;
        return;
    }
    if (LodScreen.w == 0.0 || LodCover.w == 0) return;
    vec2 ndc = gl_FragCoord.xy / LodScreen.xy * 2.0 - 1.0;
    ndc.y *= LodScreen.z;
    vec4 p = LodInvMVP * vec4(ndc, gl_FragCoord.z, 1.0);
    ivec3 b = LodCamBlock.xyz + ivec3(floor(LodCamFrac.xyz + p.xyz / p.w));
    if (b.y < 0 || b.y > 255) return;
    ivec3 s = b >> 4;
    int gx = s.x - LodCover.x, gz = s.z - LodCover.z, n = LodCover.y;
    if (gx < 0 || gz < 0 || gx >= n || gz >= n) return;
    int bit = (gz * n + gx) * 16 + s.y;
    if ((lodCoverBits[bit >> 5] & (1u << (bit & 31))) != 0u) discard;
}
//vkm-host-prelude-end

void main() {
}
