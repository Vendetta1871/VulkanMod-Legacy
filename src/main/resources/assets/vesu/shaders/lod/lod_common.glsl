// LOD terrain (AUDIT 5.15): shared declarations of lod.vsh and lod.fsh.

layout(std140, set = 0, binding = 0) uniform Frame {
    mat4 viewProj;      // jittered, camera-relative
    ivec4 camBlock;     // floor(camera)
    vec4 camFrac;       // camera - floor(camera); w: 1 in the translucent pass
    vec4 fogColor;
    vec4 fog;           // x start, y end, z mode (0 off, 1 linear, 2 exp, 3 exp2), w density
    vec4 lodFade;       // x fade start, y fade end (horizontal blocks), z atlas width, w atlas height
    ivec4 cover;        // x, z: chunk of the coverage grid's corner, y: grid size in chunks, w: 1 when valid
};

struct Model {
    uint faceData[6];
    uint flags;
    uint tint;
    uint customId;
    uint pad0, pad1, pad2, pad3, pad4, pad5, pad6;
};

layout(std430, set = 0, binding = 3) readonly buffer Models { Model models[]; };
