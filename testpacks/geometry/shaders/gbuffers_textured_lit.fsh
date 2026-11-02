#version 150 compatibility
in vec2 uv;
in vec4 color;
in vec3 tess;
uniform sampler2D tex;
void main() { vec3 f = abs(fract(tess * 3.0 + 0.5) - 0.5); gl_FragData[0] = min(f.x, min(f.y, f.z)) < 0.04 ? vec4(0.0, 1.0, 0.0, 1.0) : texture2D(tex, uv) * color; }
