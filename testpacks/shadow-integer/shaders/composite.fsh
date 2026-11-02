#version 150 compatibility
uniform sampler2D colortex0;
uniform usampler2D shadowcolor0;
in vec2 uv;
/* RENDERTARGETS: 0 */
void main() { gl_FragData[0] = uv.x < 0.125 && uv.y < 0.125 ? vec4(float(texture(shadowcolor0, vec2(0.5)).r) / 8.0, 0.0, 0.0, 1.0) : texture2D(colortex0, uv); }
