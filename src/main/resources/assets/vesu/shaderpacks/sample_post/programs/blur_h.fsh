#version 450
// Horizontal 9-tap Gaussian. Reads and writes "bloom": the host ping-pongs its two images.
layout(location = 0) out vec4 outColor;

const float W[5] = float[](0.2270270, 0.1945946, 0.1216216, 0.0540541, 0.0162162);

void main() {
    float dx = 1.0 / vkm_passSize.x;
    vec3 c = texture(bloom, vkm_texCoord).rgb * W[0];
    for (int i = 1; i < 5; i++) {
        c += texture(bloom, vkm_texCoord + vec2(dx * i, 0.0)).rgb * W[i];
        c += texture(bloom, vkm_texCoord - vec2(dx * i, 0.0)).rgb * W[i];
    }
    outColor = vec4(c, 1.0);
}
