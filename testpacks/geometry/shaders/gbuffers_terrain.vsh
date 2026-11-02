#version 150 compatibility
out vec2 uvV;
out vec4 tintV;
flat out int idV;
void main() { gl_Position = ftransform(); uvV = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy; tintV = gl_Color; idV = gl_VertexID; }
