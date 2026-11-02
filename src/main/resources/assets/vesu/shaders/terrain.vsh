#version 450

// Terrain vertex shader for the 1.12.2 DefaultVertexFormats.BLOCK layout:
//   loc 0: POSITION float3, loc 1: COLOR ubyte4 (normalized),
//   loc 2: TEX0 float2 (atlas uv), loc 3: TEX2 short2 (lightmap uv * 256)
// The chunk offset is baked into MVP/ModelViewMat by the GL matrix state, so no
// separate ChunkOffset push constant is needed here.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    int FogRadial;
    // Dynamic lights: view-space position, block light level (0 = unused); 15 lights.
    // DynLights[15].x: RT shadow range in blocks ("on" mode: ray traced inside it, so the
    // raster light fades in over its last 24 blocks); 0 = raster everywhere ("fast").
    vec4 DynLights[16];
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec2 lightCoord;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    // GL measures linear fog by eye-space depth (|z|), or by radial distance when
    // the GL_NV_fog_distance extension is present; vanilla picks the radial mode
    // through glFogi(GL_FOG_DISTANCE_MODE_NV), mirrored into FogRadial (R16).
    vertexDistance = FogRadial != 0 ? length(viewPos.xyz) : abs(viewPos.z);

    vertexColor = Color;
    texCoord0 = UV0;

    // Lightmap sampling with the +0.5 texel shift; see terrain_batch.vsh.
    // Dynamic lights raise the block light like a placed light of their level would.
    vec2 light = vec2(UV2);
    float rasterShare = 1.0;
    if (DynLights[15].x > 0.0) {
        // Camera-relative world axes: the model-view rotation is orthonormal.
        vec3 rel = transpose(mat3(ModelViewMat)) * viewPos.xyz;
        rasterShare = 1.0 - smoothstep(0.0, 1.0, (DynLights[15].x - max(abs(rel.x), abs(rel.z))) / 24.0);
    }
    if (rasterShare > 0.0) {
        for (int i = 0; i < 15; i++) {
            vec4 dl = DynLights[i];
            if (dl.w > 0.0) {
                float dyn = clamp(dl.w - distance(viewPos.xyz, dl.xyz), 0.0, 15.0) * 16.0;
                light.x = max(light.x, mix(light.x, dyn, rasterShare));
            }
        }
    }
    lightCoord = clamp(light / 256.0, vec2(0.001953126), vec2(0.96875)) + vec2(0.03125);
}
