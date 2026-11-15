// Physical sky radiance: single Rayleigh + Mie scattering of the sun and the moon through a
// spherical exponential atmosphere, plus a uniform multiple-scattering term (the mean of the
// upper hemisphere's single scattering, scaled by the view path's opacity). The model and its
// constants follow the procedural sky of NVIDIA's RTX Remix for Minecraft
// (sky_atmosphere.comp, Apache-2.0); RtLighting.skyMean evaluates the same model on the CPU.
// Below the horizon the horizon's radiance is dimmed towards the nadir (ground bounce).
//
// Units are scene units (physical radiance times RtLighting.SCENE_SCALE): the caller passes
// the sun's source radiance times the atmosphere's scattering scale.

#ifndef ATMOSPHERE_GLSL
#define ATMOSPHERE_GLSL

const float ATM_PI = 3.14159265;
const float ATM_EARTH = 6371000.0;
const float ATM_TOP = 6471000.0;
const float ATM_RAYLEIGH_H = 8000.0;
const float ATM_MIE_H = 1200.0;
const vec3 ATM_BETA_R = vec3(5.5e-6, 13.0e-6, 22.4e-6);
const float ATM_BETA_M = 21.0e-6;
const float ATM_MIE_G = 0.9;
const float ATM_SUN_HALO = 10.35;
const float ATM_MULTIPLE = 0.65;
// Moon source radiance relative to the sun's (0.06 / 30), slightly blue.
const vec3 ATM_MOON = vec3(0.85, 0.9, 1.0) * 0.002;
const int ATM_VIEW_STEPS = 12;
const int ATM_LIGHT_STEPS = 6;

// Distance to the far intersection with a sphere around the planet centre, -1 when missed.
float atmSphere(vec3 o, vec3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return -1.0;
    float s = sqrt(disc);
    float far = -b + s;
    if (far < 0.0) return -1.0;
    float near = -b - s;
    return near < 0.0 ? far : near;
}

// Transmittance from a point towards a light, 0 when the planet blocks it.
vec3 atmLightTransmittance(vec3 p, vec3 l) {
    float r = length(p);
    float mu = dot(p, l) / r;
    if (mu < 0.0 && r * r * (1.0 - mu * mu) < ATM_EARTH * ATM_EARTH) return vec3(0.0);
    float len = atmSphere(p, l, ATM_TOP);
    if (len <= 0.0) return vec3(1.0);
    float step = len / float(ATM_LIGHT_STEPS);
    float dr = 0.0, dm = 0.0;
    for (int i = 0; i < ATM_LIGHT_STEPS; i++) {
        float h = max(length(p + l * ((float(i) + 0.5) * step)) - ATM_EARTH, 0.0);
        dr += exp(-h / ATM_RAYLEIGH_H);
        dm += exp(-h / ATM_MIE_H);
    }
    return exp(-(ATM_BETA_R * dr + ATM_BETA_M * dm) * step);
}

float atmPhaseR(float c) { return 3.0 / (16.0 * ATM_PI) * (1.0 + c * c); }
float atmPhaseM(float c) {
    float g2 = ATM_MIE_G * ATM_MIE_G;
    return 3.0 / (8.0 * ATM_PI) * ((1.0 - g2) * (1.0 + c * c))
            / ((2.0 + g2) * pow(1.0 + g2 - 2.0 * ATM_MIE_G * c, 1.5));
}

// Single scattering along an upward (or horizontal) view direction; viewT: the path's transmittance.
vec3 atmSingle(vec3 dir, vec3 sunDir, float source, out vec3 viewT) {
    vec3 o = vec3(0.0, ATM_EARTH + 1.0, 0.0);
    float len = atmSphere(o, dir, ATM_TOP);
    float step = len / float(ATM_VIEW_STEPS);
    vec3 dr = vec3(0.0), sunR = vec3(0.0), sunM = vec3(0.0), moonR = vec3(0.0), moonM = vec3(0.0);
    float odR = 0.0, odM = 0.0;
    for (int i = 0; i < ATM_VIEW_STEPS; i++) {
        vec3 p = o + dir * ((float(i) + 0.5) * step);
        float h = max(length(p) - ATM_EARTH, 0.0);
        float rr = exp(-h / ATM_RAYLEIGH_H) * step, rm = exp(-h / ATM_MIE_H) * step;
        odR += rr;
        odM += rm;
        vec3 t = exp(-(ATM_BETA_R * odR + ATM_BETA_M * odM));
        vec3 ts = atmLightTransmittance(p, sunDir) * t;
        vec3 tm = atmLightTransmittance(p, -sunDir) * t;
        sunR += rr * ts; sunM += rm * ts;
        moonR += rr * tm; moonM += rm * tm;
    }
    viewT = exp(-(ATM_BETA_R * odR + ATM_BETA_M * odM));
    float cs = dot(dir, sunDir);
    vec3 sun = (ATM_BETA_R * sunR * atmPhaseR(cs) + ATM_BETA_M * sunM * atmPhaseM(cs) * ATM_SUN_HALO);
    vec3 moon = (ATM_BETA_R * moonR * atmPhaseR(-cs) + ATM_BETA_M * moonM * atmPhaseM(-cs)) * ATM_MOON;
    return (sun + moon) * source;
}

// Sky radiance seen along dir (world axes, y up). sunDir: towards the sun (may be below the
// horizon); source: the sun's source radiance times the scattering scale; upperMean: the mean
// single scattering of the upper hemisphere.
vec3 atmosphere(vec3 dir, vec3 sunDir, float source, vec3 upperMean) {
    float below = clamp(-dir.y, 0.0, 1.0);
    vec3 d = dir;
    if (d.y < 0.0) d = normalize(vec3(dir.x, 0.0, dir.z) + vec3(1e-5, 0.0, 0.0));
    vec3 viewT;
    vec3 single = atmSingle(d, sunDir, source, viewT);
    vec3 radiance = max(single + upperMean * (1.0 - viewT) * ATM_MULTIPLE, vec3(0.0));
    if (dir.y < 0.0) radiance *= mix(0.9, 0.3, asin(below) / (0.5 * ATM_PI));
    return radiance;
}

// Transmittance along dir from the ground (the sun disc's color).
vec3 atmViewTransmittance(vec3 dir) {
    return atmLightTransmittance(vec3(0.0, ATM_EARTH + 1.0, 0.0), dir);
}

#endif
