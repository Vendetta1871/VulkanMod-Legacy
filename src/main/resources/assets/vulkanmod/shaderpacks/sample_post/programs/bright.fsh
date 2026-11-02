#version 450
// Bright pass at half resolution: a 4-tap box filter of the scene, then a soft threshold.
#include <lib/common.glsl>

layout(location = 0) out vec4 outColor;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(sceneColor, 0));
    vec3 c = texture(sceneColor, vkm_texCoord + texel * vec2(-0.5, -0.5)).rgb
           + texture(sceneColor, vkm_texCoord + texel * vec2( 0.5, -0.5)).rgb
           + texture(sceneColor, vkm_texCoord + texel * vec2(-0.5,  0.5)).rgb
           + texture(sceneColor, vkm_texCoord + texel * vec2( 0.5,  0.5)).rgb;
    c = min(c * 0.25, vec3(16.0));
    float l = luma(c);
    // Soft knee around 0.7. The scene is HDR: additive sun, moon and glints exceed 1 and
    // carry most of the bloom.
    float knee = clamp((l - 0.6) / 0.3, 0.0, 1.0);
    outColor = vec4(c * knee * knee, l);
}
