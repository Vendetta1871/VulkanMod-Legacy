#version 150 compatibility
/*
const int shadowcolor0Format = R32UI;
*/
layout(location = 0) out uvec4 c;
/* RENDERTARGETS: 0 */
void main() { c = uvec4(7u); }
