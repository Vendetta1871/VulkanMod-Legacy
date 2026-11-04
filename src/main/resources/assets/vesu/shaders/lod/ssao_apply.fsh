#version 450
// LOD SSAO apply: the blend multiplies the scene color by the occlusion term.
layout(set = 0, binding = 0, r32f) uniform readonly image2D ao;
layout(location = 0) out vec4 fragColor;

void main() {
    // The occlusion is at half resolution. A 4x4 box of its texels around the pixel removes
    // the 4x4 rotation pattern of the compute pass; texels without a LOD pixel (-1) stay out,
    // so the occlusion does not spread onto the sky or vanilla terrain.
    ivec2 c = ivec2(gl_FragCoord.xy) >> 1, m = imageSize(ao) - 1;
    if (imageLoad(ao, min(c, m)).r < 0.0) {
        fragColor = vec4(1.0);
        return;
    }
    float a = 0.0, n = 0.0;
    for (int y = -1; y < 3; y++)
        for (int x = -1; x < 3; x++) {
            float v = imageLoad(ao, clamp(c + ivec2(x - 1, y - 1) + 1, ivec2(0), m)).r;
            if (v >= 0.0) {
                a += v;
                n += 1.0;
            }
        }
    a = n > 0.0 ? a / n : 1.0;
    fragColor = vec4(a, a, a, 1.0);
}
