#version 450
// Vertical 9-tap Gaussian as a compute pass: samples the old "bloom" image, stores into the
// new one (bloomOut, the host's name for a texture a compute pass reads and writes).
const float W[5] = float[](0.2270270, 0.1945946, 0.1216216, 0.0540541, 0.0162162);

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(bloomOut);
    if (any(greaterThanEqual(p, size))) return;
    vec2 uv = (vec2(p) + 0.5) / vec2(size);
    float dy = 1.0 / float(size.y);
    vec3 c = textureLod(bloom, uv, 0.0).rgb * W[0];
    for (int i = 1; i < 5; i++) {
        c += textureLod(bloom, uv + vec2(0.0, dy * i), 0.0).rgb * W[i];
        c += textureLod(bloom, uv - vec2(0.0, dy * i), 0.0).rgb * W[i];
    }
    imageStore(bloomOut, p, vec4(c, 1.0));
}
