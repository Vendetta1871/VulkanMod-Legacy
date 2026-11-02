#version 450
// Combination: scene + bloom, exposure, a soft highlight shoulder, light grading, distance
// haze in the rain and a vignette. Writes the host scene color.
#include <lib/common.glsl>

layout(location = 0) out vec4 outColor;

vec3 shoulder(vec3 x) {
    // Identity below 0.8, then rolls off towards 1 instead of clipping.
    const float k = 0.8;
    vec3 over = max(x - k, 0.0);
    return min(x, vec3(k)) + (1.0 - k) * (1.0 - exp(-over / (1.0 - k)));
}

void main() {
    vec3 c = texture(sceneColor, vkm_texCoord).rgb;
    vec3 b = texture(bloom, vkm_texCoord).rgb;
    float avg = texelFetch(exposure, ivec2(0), 0).r;
    float e = clamp(0.45 / max(avg, 1e-3), 0.85, 1.25);
    vec3 x = (c + b * 0.4) * e;

    // Distant geometry fades towards the fog color in the rain.
    float depth = texture(sceneDepth, vkm_texCoord).r;
    if (depth < 1.0) {
        float dist = vkm_linearDepth(depth);
        float haze = rainStrength * clamp(dist / max(far, 1.0), 0.0, 1.0) * 0.5;
        x = mix(x, fogColor, haze);
    }

    x = shoulder(x);
    float l = luma(x);
    x = mix(vec3(l), x, 1.08);                       // a little saturation
    x = (x - 0.5) * 1.04 + 0.5;                      // and contrast
    vec2 v = vkm_texCoord - 0.5;
    x *= 1.0 - 0.18 * smoothstep(0.25, 0.75, dot(v, v) * 2.0);
    outColor = vec4(clamp(x, 0.0, 1.0), 1.0);
}
