#version 450
// Quarter-resolution downsample of the bright pass (its first mip is half of it again).
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(textureLod(bright, vkm_texCoord, 1.0).rgb, 1.0);
}
