#version 150 compatibility
uniform sampler2D colortex0, colortex3, colortex4, colortex5, colortex6, shadowcolor0;
in vec2 uv;
/* RENDERTARGETS: 0 */
out vec4 color;
void main() {
    color = texture(colortex0, uv);
    if (uv.x >= 0.2 && uv.x < 0.3) color = texture(colortex3, uv);
    if (uv.x >= 0.3 && uv.x < 0.4) color = texture(colortex4, uv);
    if (uv.x >= 0.4 && uv.x < 0.5) color = texture(colortex5, uv);
    if (uv.x >= 0.5 && uv.x < 0.6) color = texture(colortex6, uv);
    if (uv.x >= 0.6 && uv.x < 0.7) color = texture(shadowcolor0, uv * 64.0 / textureSize(shadowcolor0, 0));
}
