#version 450

layout(binding = 1) uniform UniformBufferObject {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    int FogMode;
    float FogDensity;
    float AlphaFunc;
    float AlphaRef;
    // Horizontal band where vanilla terrain dissolves into the LODs; start >= end: off.
    float LodEdgeStart;
    float LodEdgeEnd;
};

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec2 lightCoord;
layout(location = 4) in vec2 horizontal;

layout(location = 0) out vec4 fragColor;

void main() {
    if (LodEdgeEnd > LodEdgeStart) {
        // Screen-space noise shared with lod.fsh, which keeps exactly the pixels dropped here.
        float t = smoothstep(LodEdgeStart, LodEdgeEnd, length(horizontal));
        if (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) < t) discard;
    }
    vec4 color = vertexColor * ColorModulator * texture(Sampler0, texCoord0);

    // GL fixed-function alpha test with the live func/ref: keep the fragment only
    // when compare(color.a, AlphaRef) holds. AlphaFunc carries the GL compare enum
    // (NEVER 512 ... ALWAYS 519); 0 means the test is disabled (M21, N23).
    int alphaFunc = int(AlphaFunc);
    if (alphaFunc != 0) {
        bool keep;
        if (alphaFunc == 512) keep = false;                                   // NEVER
        else if (alphaFunc == 513) keep = color.a < AlphaRef;                 // LESS
        else if (alphaFunc == 514) keep = abs(color.a - AlphaRef) < 0.0039;   // EQUAL
        else if (alphaFunc == 515) keep = color.a <= AlphaRef;                // LEQUAL
        else if (alphaFunc == 516) keep = color.a > AlphaRef;                 // GREATER
        else if (alphaFunc == 517) keep = abs(color.a - AlphaRef) >= 0.0039;  // NOTEQUAL
        else if (alphaFunc == 518) keep = color.a >= AlphaRef;                // GEQUAL
        else keep = true;                                                      // ALWAYS
        if (!keep) {
            discard;
        }
    }

    // Vanilla lightmap: the fixed-function path modulates the block colour with the
    // lightmap texture directly (GL_MODULATE on the lightmap unit, lighting disabled),
    // so no scaling is applied here. The lightmap saturates to ~white in full daylight,
    // which is why any extra factor is invisible at noon but brightens dusk, night and
    // caves by the same factor (the dumped table reads 57/255 for an exposed block at
    // midnight, i.e. vanilla renders night terrain at ~0.22).
    vec4 lightmap = texture(Sampler2, lightCoord);
    color.rgb *= lightmap.rgb;

    // GL fog replaces only the fragment RGB; the alpha (the blend
    // weight of translucent draws) passes through untouched.
    if (FogMode == 1) {
        float f = clamp((FogEnd - vertexDistance) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
        color.rgb = mix(FogColor.rgb, color.rgb, f);
    }
    else if (FogMode == 2) {
        float f = clamp(exp(-FogDensity * vertexDistance), 0.0, 1.0);
        color.rgb = mix(FogColor.rgb, color.rgb, f);
    }
    else if (FogMode == 3) {
        float d = FogDensity * vertexDistance;
        float f = clamp(exp(-d * d), 0.0, 1.0);
        color.rgb = mix(FogColor.rgb, color.rgb, f);
    }

    fragColor = color;
}
