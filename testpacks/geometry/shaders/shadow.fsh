#version 150 compatibility
in vec2 uv;
uniform sampler2D tex;
void main() { gl_FragData[0] = texture2D(tex, uv); }
