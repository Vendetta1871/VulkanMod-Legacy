#version 150 compatibility
uniform usampler2D shadowcolor0;
in vec2 uv;
layout(location = 0) out uvec4 c;
/* RENDERTARGETS: 0 */
void main() { c = texture(shadowcolor0, uv) + uvec4(1u); }
