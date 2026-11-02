#version 150 compatibility
out vec2 uvV;
void main() { gl_Position = ftransform(); uvV = gl_MultiTexCoord0.xy; }
