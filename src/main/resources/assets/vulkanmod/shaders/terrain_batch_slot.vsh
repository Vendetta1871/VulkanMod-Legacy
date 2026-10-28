#version 450

// Diagnostic variant of terrain_batch.vsh: passes the section slot to the fragment
// shader so the screen can be colored per chunk section (crack-seam debugging).

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
layout(location = 4) in float ChunkSlot;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    int FogRadial;
    // Dynamic lights ("fast" mode): view-space position, block light level (0 = unused).
    vec4 DynLights[16];
};

struct ChunkMatrix {
    mat4 mvp;
    mat4 modelView;
};

layout(std430, binding = 4) readonly buffer ChunkMatrices {
    ChunkMatrix chunks[];
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec2 lightCoord;
layout(location = 4) out float slotOut;

void main() {
    ChunkMatrix cm = chunks[int(ChunkSlot)];

    vec4 viewPos = cm.modelView * vec4(Position, 1.0);
    gl_Position = cm.mvp * vec4(Position, 1.0);

    vertexDistance = FogRadial != 0 ? length(viewPos.xyz) : abs(viewPos.z);
    vertexColor = Color;
    texCoord0 = UV0;
    // Dynamic lights raise the block light like a placed light of their level would.
    vec2 light = vec2(UV2);
    for (int i = 0; i < 16; i++) {
        vec4 dl = DynLights[i];
        if (dl.w > 0.0) light.x = max(light.x, clamp(dl.w - distance(viewPos.xyz, dl.xyz), 0.0, 15.0) * 16.0);
    }
    lightCoord = clamp(light / 256.0, vec2(0.001953126), vec2(0.96875));
    slotOut = ChunkSlot;
}
