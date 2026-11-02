#version 120
uniform sampler2D texture;
void main() { gl_FragData[0] = texture2D(texture, gl_TexCoord[0].xy) * gl_Color; }
