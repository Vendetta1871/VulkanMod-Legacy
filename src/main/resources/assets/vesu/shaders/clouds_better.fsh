#version 450

// Better clouds (BetterClouds): the colour is a lookup in the light-gradient texture indexed
// by the angle between the view ray and the sun (U) and the time of day (V), with a
// sun/moon halo reshaping the lookup near the sun and anti-sun points, multiplied by the
// bottom-to-top tint. Alpha is the per-cube opacity faded by distance. Straight alpha.

layout(binding = 1) uniform UniformBufferObject {
    // [0] opacity, opacityFactor, opacityExponent, brightness
    // [1] gamma (effective), saturation, halo size h, V = mappedTime / 24000
    // [2] top colour (tinted).rgb, sin(sunPathAngle)
    // [3] bottom colour (tinted).rgb, cos(sunPathAngle)
    // [4] sun direction.xyz, halo enabled (0/1)
    // [5] colour scale (ray tracing: vanilla white in scene units), distant-terrain depth
    //     compare (0 off, 1 forward depth, 2 reverse-Z), unused, unused
    vec4 BcFrag[6];
};

// Light gradient: 32x32, bilinear; U never leaves the texel-center inset, V repeats.
layout(binding = 3) uniform sampler2D Sampler1;

// A shader pack's distant-terrain depth (dhDepthTex0, its own projection); white when unused.
layout(binding = 4) uniform sampler2D Sampler2;

layout(location = 0) in float fogFade;
layout(location = 1) in float tintT;
layout(location = 2) in vec3 lightDir;
layout(location = 3) in vec4 dhPos;

layout(location = 0) out vec4 fragColor;

void main() {
    // Behind distant terrain drawn into its own depth: hidden (the scene depth test covers the rest).
    if (BcFrag[5].y > 0.5) {
        float z = dhPos.z / dhPos.w;
        float stored = texelFetch(Sampler2, ivec2(gl_FragCoord.xy), 0).r;
        if (BcFrag[5].y < 1.5 ? stored < z : stored > z) discard;
    }

    vec3 n = normalize(lightDir);
    vec3 sun = BcFrag[4].xyz;
    float s = dot(sun, n);

    // 1. Light coordinate, with the superellipse halo around the sun and the anti-sun point.
    float x;
    if (BcFrag[4].w > 0.5) {
        vec3 q = n - sun * s;
        q /= max(length(q), 1e-6);
        vec3 axis = vec3(0.0, BcFrag[2].w, BcFrag[3].w);
        float cosPsi = dot(q, axis);
        float k = 2.0 * cosPsi * cosPsi - 1.0;
        float h = BcFrag[1].z;
        float e = ((1.0 + k * k / 3.0) * h * (1.0 - abs(s)) - 1.0) * sign(-s);
        x = mix(s, e, smoothstep(0.75, 1.0, abs(s)));
    } else {
        x = 0.9 * s;
    }

    // 2. Piecewise map to U (sun side on the left), inset half a texel.
    float u;
    if (x > 0.5) {
        u = 0.75 * (1.0 - x);
    } else if (x > -0.5) {
        u = 0.375 + 0.25 * (0.5 - x);
    } else {
        u = 0.625 + 0.375 * (-2.0 * x - 1.0);
    }
    u -= (u - 0.5) / 32.0;

    // 3./4. Lookup, saturation, brightness and gamma on the luma.
    vec3 L = texture(Sampler1, vec2(u, BcFrag[1].w)).rgb;
    float Yl = dot(L, vec3(0.2126, 0.7152, 0.072)) + 0.001;
    vec3 Kl = mix(vec3(1.0), L / Yl, BcFrag[1].y);
    float Ylp = pow(Yl * BcFrag[0].w, 1.0 / BcFrag[1].x);
    vec3 light = Kl * Ylp;

    // 5. Bottom-to-top colour.
    vec3 base = mix(BcFrag[3].rgb, BcFrag[2].rgb, tintT);

    // 6. Alpha.
    float alpha = BcFrag[0].y * BcFrag[0].x * pow(fogFade, BcFrag[0].z);
    fragColor = vec4(light * base * BcFrag[5].x, alpha);
}
