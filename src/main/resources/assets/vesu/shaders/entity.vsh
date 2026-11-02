#version 450

// Entity vertex shader (DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL):
//   loc 0: POSITION float3, loc 1: TEX0 float2, loc 2: NORMAL byte3 (SNORM),
//   loc 3: PADDING byte1 (alignment, unused).
// Mob model parts are rebuilt into the shared BufferBuilder every frame (see
// ModelRendererM), so the live GL modelview already carries the camera rotation,
// the entity translate and the per-part rotation baked into the vertex position.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec3 Normal;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    mat4 ModelViewMat;
    vec3 Light0_Direction;
    vec3 Light1_Direction;
    int FogRadial;
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float lightAccum;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = FogRadial != 0 ? length(viewPos.xyz) : abs(viewPos.z);
    texCoord0 = UV0;

    // RenderHelper.enableStandardItemLighting(): two directional lights of 0.6 diffuse
    // each over the 0.4 light-model ambient. colorMaterial(GL_FRONT_AND_BACK,
    // GL_AMBIENT_AND_DIFFUSE) makes the material ambient track the vertex colour too
    // (not just the diffuse), so the ambient term is the full 0.4 rather than the 0.2
    // GL default material ambient times 0.4.
    //
    // Evaluated per vertex because vanilla renders entities with shadeModel(SMOOTH);
    // every model quad carries one flat face normal, so this still yields the familiar
    // per-face shading. Entities use a uniform scale, so normalizing the modelview
    // rotated normal cancels the scale factor. Normal and lights are both in eye space.
    vec3 normal = normalize(mat3(ModelViewMat) * Normal);
    float diffuse = max(0.0, dot(normalize(Light0_Direction), normal))
                  + max(0.0, dot(normalize(Light1_Direction), normal));

    lightAccum = clamp(0.4 + 0.6 * diffuse, 0.0, 1.0);
}
