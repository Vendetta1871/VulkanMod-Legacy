// Shared by the rx_*.comp passes: a port of NVIDIA RTX Remix's post chain (dxvk-remix,
// src/dxvk/shaders/rtx/pass/{bloom,tonemap,local_tonemap}, MIT) as the runtime of NVIDIA's
// RTX Remix for Minecraft configures it (packaging/rtx.conf). PostProcess.java dispatches them.
// Bindings of every pass: 0 storage output, 1-4 sampled inputs (linear, clamp to edge),
// 5 the exposure (r: current exposure), 6 the exposure histogram (256 buckets).

const float SRGB_GAMMA = 2.2;
const int HISTOGRAM_SIZE = 256;

float bt709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 linearToGamma(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / SRGB_GAMMA)); }
float luminanceEV100(float l) { return log2(l * (100.0 / 12.5)); }
float ev100Luminance(float ev) { return exp2(ev) / (100.0 / 12.5); }

// ACES fitted (Stephen Hill), dxvk-remix ACES.hlsl; GLSL matrices are column-major, so the
// HLSL rows are written as columns here.
const mat3 ACES_IN = mat3(0.59719, 0.07600, 0.02840,
                          0.35458, 0.90834, 0.13383,
                          0.04823, 0.01566, 0.83777);
const mat3 ACES_OUT = mat3(1.60475, -0.10208, -0.00327,
                           -0.53108, 1.10813, -0.07276,
                           -0.07367, -0.00605, 1.07602);
vec3 acesFitted(vec3 color, bool suppressBlackLevelClamp) {
    vec3 v = ACES_IN * color;
    vec3 a = v * (v + 0.0245786) - (suppressBlackLevelClamp ? 0.0 : 0.000090537);
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return clamp(ACES_OUT * (a / b), 0.0, 1.0);
}
