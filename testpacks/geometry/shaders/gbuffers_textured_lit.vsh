#version 150 compatibility
out vec2 uvV;
out vec4 colorV;
void main() { gl_Position = ftransform(); uvV = gl_MultiTexCoord0.xy; colorV = gl_Color; }
