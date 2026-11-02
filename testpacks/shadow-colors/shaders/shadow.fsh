#version 150 compatibility
const bool shadowcolor0Mipmap = true;
/* RENDERTARGETS: 0,2,5 */
void main() { gl_FragData[0] = vec4(1.0, 0.0, 0.0, 1.0); gl_FragData[1] = vec4(0.0, 0.5, 0.0, 1.0); gl_FragData[2] = vec4(0.25, 0.25, 0.75, 1.0); }
