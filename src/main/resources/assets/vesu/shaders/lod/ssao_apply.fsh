#version 450
// LOD SSAO apply: the blend multiplies the scene color by the occlusion term.
layout(set = 0, binding = 0, r32f) uniform readonly image2D ao;
layout(location = 0) out vec4 fragColor;

void main() {
    // 4x4 box around the pixel: removes the 4x4 rotation pattern of the compute pass.
    ivec2 p = ivec2(gl_FragCoord.xy) - 1, m = imageSize(ao) - 1;
    float a = 0.0;
    for (int y = -1; y < 3; y++)
        for (int x = -1; x < 3; x++) a += imageLoad(ao, clamp(p + ivec2(x, y), ivec2(0), m)).r;
    a /= 16.0;
    fragColor = vec4(a, a, a, 1.0);
}
