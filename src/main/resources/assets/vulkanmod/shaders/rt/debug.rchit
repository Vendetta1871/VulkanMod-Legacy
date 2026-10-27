#version 460
#extension GL_EXT_ray_tracing : require
// Colors each section by its instance index, darkens with distance, outlines triangles.

layout(location = 0) rayPayloadInEXT vec4 payload;
hitAttributeEXT vec2 bary;

vec3 hashColor(uint i) {
    i = (i ^ 61u) ^ (i >> 16);
    i *= 9u;
    i ^= i >> 4;
    i *= 0x27d4eb2du;
    i ^= i >> 15;
    return vec3(float(i & 255u), float((i >> 8) & 255u), float((i >> 16) & 255u)) / 255.0 * 0.6 + 0.3;
}

void main() {
    vec3 b = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float edge = min(min(b.x, b.y), b.z) < 0.02 ? 0.4 : 1.0;
    float fade = clamp(1.0 - gl_HitTEXT / 256.0, 0.15, 1.0);
    payload = vec4(hashColor(uint(gl_InstanceCustomIndexEXT)) * fade * edge, 1.0);
}
