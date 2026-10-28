#version 450

// Particle billboard vertex shader (PARTICLE_POSITION_TEX_COLOR_LMAP):
//   loc 0: POSITION float3, loc 1: TEX0 float2, loc 2: COLOR ubyte4 (UNORM),
//   loc 3: LIGHTMAP short2 (the particle's packed block/sky light, 0..240).
// ParticleManager builds the quads in camera-relative world space, so the live
// modelview/projection apply directly. No directional shading: particles are
// full-bright except for the lightmap, which the fragment shader samples.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 VertexColor;
layout(location = 3) in ivec2 LightMapCoord;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    int FogRadial;
    // Dynamic lights (fast and ray-traced modes): view-space position, level (0 = unused).
    vec4 DynLightsAll[16];
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 lightCoord;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = FogRadial != 0 ? length(viewPos.xyz) : abs(viewPos.z);
    texCoord0 = UV0;
    vertexColor = VertexColor;

    // Same shifted-LINEAR lightmap sampling as the terrain shaders (see
    // terrain_batch.vsh); particles share the lightmap texture and sampler.
    vec2 light = vec2(LightMapCoord);
    for (int i = 0; i < 16; i++) {
        vec4 dl = DynLightsAll[i];
        if (dl.w > 0.0) light.x = max(light.x, clamp(dl.w - distance(viewPos.xyz, dl.xyz), 0.0, 15.0) * 16.0);
    }
    lightCoord = clamp(light / 256.0, vec2(0.001953126), vec2(0.96875)) + vec2(0.03125);
}
