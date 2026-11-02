#version 450
// Eye adaptation: the log-average scene luminance (from a small mip of the bright pass,
// whose alpha holds the luminance), eased towards over time. "exposurePrev" is the value
// of the previous frame, kept by the host because the texture has "history".
void main() {
    float lod = max(0.0, float(textureQueryLevels(bright)) - 4.0);
    float sum = 0.0;
    for (int y = 0; y < 8; y++) {
        for (int x = 0; x < 8; x++) {
            vec2 uv = (vec2(x, y) + 0.5) / 8.0;
            sum += log(max(textureLod(bright, uv, lod).a, 1e-3));
        }
    }
    float current = exp(sum / 64.0);
    float prev = texelFetch(exposurePrev, ivec2(0), 0).r;
    // The first frame (and any garbage) starts from the current value.
    if (isnan(prev) || isinf(prev) || prev <= 0.0 || prev > 16.0) prev = current;
    float k = 1.0 - exp(-clamp(frameTime, 0.0, 0.25) * 1.5);
    imageStore(exposure, ivec2(0), vec4(mix(prev, current, k)));
}
