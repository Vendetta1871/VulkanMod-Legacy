#version 150 compatibility
uniform sampler2D shadowcolor0;
uniform sampler2D shadowcolor1;
in vec2 texcoord;
/* RENDERTARGETS: 0,1 */
void main() { vec4 c = texture2D(shadowcolor0, texcoord); gl_FragData[0] = c * 0.5; gl_FragData[1] = vec4(0.0, 0.0, 1.0, 1.0); }
