#version 450

// Diagnostic: ignores all vertex attributes and matrices. Maps the quad index
// corners onto the full screen so the fragment shader shows the raw atlas.

layout(location = 2) out vec2 texCoord0;

void main() {
    uint i = gl_VertexIndex % 4u;
    vec2 p = vec2(float(i & 1u), float((i >> 1) & 1u));
    gl_Position = vec4(p.x * 2.0 - 1.0, p.y * 2.0 - 1.0, 0.0, 1.0);
    texCoord0 = p;
}
