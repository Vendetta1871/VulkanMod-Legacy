#version 450
// Host source of the fragment stage of the pack programs that draw distant terrain
// (AUDIT 5.16). The pack shades from its vertex outputs. Distant water draws with the
// distant terrain's depth only, so after the pack's main its fragments inside a chunk
// section vanilla has built are dropped: coarse water spreads over terrain there. Opaque
// distant terrain keeps them: vanilla terrain draws over it, and packs fade vanilla terrain
// out near its edge for DH to fill.

layout(std140, binding = 0) uniform LodPackFrame {
    mat4 MVP;
    mat4 ModelViewMat;
    ivec4 LodCamBlock;
    vec4 LodCamFrac;
    mat4 LodInvMVP;
    ivec4 LodCover;     // x, z: chunk of the coverage grid's corner, y: grid size in chunks, w: 1 when valid
    vec4 LodScreen;     // x, y: size in pixels, z: -1 when the viewport flips y
};
layout(std430, binding = 5) readonly buffer LodCoverBits { uint lodCoverBits[]; };

//vkm-host-prelude-begin
#define VKM_LOD_DISCARD

void vkm_lodDiscard() {
    // Only distant water (MC_RENDER_STAGE_TERRAIN_TRANSLUCENT).
    if (renderStage != 17 || LodCover.w == 0) return;
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
