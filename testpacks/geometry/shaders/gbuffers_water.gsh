#version 150 compatibility
#extension GL_ARB_geometry_shader4 : enable
const int maxVerticesOut = 3;
void main() {
    for (int i = 0; i < gl_VerticesIn; i++) {
        gl_Position = gl_PositionIn[i];
        gl_TexCoord[0] = gl_TexCoordIn[i][0];
        gl_FrontColor = gl_FrontColorIn[i] * 0.001 + vec4(0.0, 1.0, 1.0, 1.0);
        EmitVertex();
    }
    EndPrimitive();
}
