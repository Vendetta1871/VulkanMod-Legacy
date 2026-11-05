#version 450

// GPU particle billboards (GpuParticles): one instance record per particle, expanded here
// into the quad Particle.renderParticle builds on the CPU. Corner c of quad index 0..3:
//   0: -R -U (maxU, maxV)   1: -R +U (maxU, minV)   2: +R +U (minU, minV)   3: +R -U (minU, maxV)
// with R and U vanilla's rotation parameters (ActiveRenderInfo) scaled by the half size, then
// rolled about the view direction by the particle's angle exactly as vanilla's quaternion does.
// The rest matches entity_particle.vsh.

layout(location = 0) in vec3 Center;
layout(location = 1) in vec2 SizeAngle;
layout(location = 2) in vec2 UvMin;
layout(location = 3) in vec2 UvMax;
layout(location = 4) in vec4 VertexColor;
layout(location = 5) in ivec2 LightMapCoord;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    int FogRadial;
    vec4 DynLightsAll[16];
    // xyz: camera right, camera up, view direction.
    vec4 ParticleAxes[3];
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 lightCoord;

void main() {
    int c = gl_VertexIndex & 3;
    float sr = c < 2 ? -1.0 : 1.0;
    float su = (c == 0 || c == 3) ? -1.0 : 1.0;
    vec3 offset = (sr * ParticleAxes[0].xyz + su * ParticleAxes[1].xyz) * SizeAngle.x;
    if (SizeAngle.y != 0.0) {
        vec3 u = sin(SizeAngle.y * 0.5) * ParticleAxes[2].xyz;
        float s = cos(SizeAngle.y * 0.5);
        offset = 2.0 * dot(offset, u) * u + (s * s - dot(u, u)) * offset + 2.0 * s * cross(u, offset);
    }
    vec3 position = Center + offset;

    vec4 viewPos = ModelViewMat * vec4(position, 1.0);
    gl_Position = MVP * vec4(position, 1.0);

    vertexDistance = FogRadial != 0 ? length(viewPos.xyz) : abs(viewPos.z);
    texCoord0 = vec2(c < 2 ? UvMax.x : UvMin.x, (c == 0 || c == 3) ? UvMax.y : UvMin.y);
    vertexColor = VertexColor;

    vec2 light = vec2(LightMapCoord);
    for (int i = 0; i < 16; i++) {
        vec4 dl = DynLightsAll[i];
        if (dl.w > 0.0) light.x = max(light.x, clamp(dl.w - distance(viewPos.xyz, dl.xyz), 0.0, 15.0) * 16.0);
    }
    lightCoord = clamp(light / 256.0, vec2(0.001953126), vec2(0.96875)) + vec2(0.03125);
}
